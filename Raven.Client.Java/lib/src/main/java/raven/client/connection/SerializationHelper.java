package raven.client.connection;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpStatus;
import org.apache.http.impl.cookie.DateParseException;
import org.apache.http.impl.cookie.DateUtils;

import raven.abstractions.data.Attachment;
import raven.abstractions.data.Constants;
import raven.abstractions.data.Etag;
import raven.abstractions.data.JsonDocument;
import raven.abstractions.data.JsonDocumentMetadata;
import raven.abstractions.data.QueryResult;
import raven.abstractions.extensions.JsonExtensions;
import raven.abstractions.extensions.MetadataExtensions;
import raven.abstractions.json.linq.JTokenType;
import raven.abstractions.json.linq.RavenJArray;
import raven.abstractions.json.linq.RavenJObject;
import raven.abstractions.json.linq.RavenJToken;

public class SerializationHelper {

  /**
   * Translate a collection of RavenJObject to JsonDocuments
   * @param responses
   * @return
   */
  public static List<JsonDocument> ravenJObjectsToJsonDocuments(Collection<RavenJObject> responses) {
    List<JsonDocument> list = new ArrayList<>();
    for (RavenJObject doc: responses) {
      if (doc == null) {
        list.add(null);
        continue;
      }
      JsonDocument jsonDocument = ravenJObjectToJsonDocument(doc);
      list.add(jsonDocument);
    }

    return list;
  }

  public static List<JsonDocument> ravenJObjectsToJsonDocuments(RavenJToken responseJson) {
    List<JsonDocument> list = new ArrayList<>();

    RavenJArray jArray = (RavenJArray) responseJson;
    for (RavenJToken token : jArray) {
      if (token == null) {
        list.add(null);
        continue;
      }

      RavenJObject tokenObject = (RavenJObject) token;
      list.add(ravenJObjectToJsonDocument(tokenObject));
    }
    return list;
  }

  private static Date getLastModified(RavenJObject metadata) {
    if (metadata == null) {
      return new Date();
    }
    Date date = null;
    if (metadata.containsKey(Constants.RAVEN_LAST_MODIFIED)) {
      date = metadata.value(Date.class, Constants.RAVEN_LAST_MODIFIED);
    } else {
      date = metadata.value(Date.class, Constants.LAST_MODIFIED);
    }
    if (date == null) {
      date = new Date();
    }
    return date;
  }

  private static Date getLastModifiedDate(Map<String, String> headers) {

    String ravenLastModified = headers.get(Constants.RAVEN_LAST_MODIFIED);
    if (StringUtils.isNotEmpty(ravenLastModified)) {
      try {
        return new SimpleDateFormat(Constants.RAVEN_LAST_MODIFIED_DATE_FORMAT).parse(ravenLastModified);
      } catch (ParseException e) {
        throw new IllegalArgumentException(e.getMessage(), e);
      }
    }
    String lastModified = headers.get(Constants.LAST_MODIFIED);
    if (StringUtils.isNotEmpty(lastModified)) {
      try {
        return DateUtils.parseDate(lastModified);
      } catch (DateParseException e) {
        throw new IllegalArgumentException(e.getMessage(), e);
      }
    }

    return null;
  }

  private static <T> T extract(RavenJObject metadata, String key, T defaultValue, Class<T> expectedClass) {
    if (metadata == null || !metadata.containsKey(key)) {
      return defaultValue;
    }
    if (JTokenType.ARRAY == metadata.get(key).getType()) {
      return defaultValue;
    }
    return metadata.value(expectedClass, key);

  }

  /**
   * Java only
   * @param responseJson
   * @return
   */
  public static List<Attachment> deserializeAttachements(RavenJToken responseJson, boolean canReadData) {
    RavenJArray array = (RavenJArray) responseJson;

    List<Attachment> result = new ArrayList<>();
    for (RavenJToken token: array) {
      int size = token.value(Integer.TYPE, "Size");
      RavenJObject metadata = token.value(RavenJObject.class, "Metadata");
      Etag etag = token.value(Etag.class, "Etag");
      String key = token.value(String.class, "Key");

      Attachment attachment = new Attachment(canReadData, (byte[])null, size, metadata, etag, key);
      result.add(attachment);
    }
    return result;
  }

  public static JsonDocumentMetadata deserializeJsonDocumentMetadata(String docKey, Map<String, String> headers, int responseStatusCode) {
    RavenJObject meta = MetadataExtensions.filterHeaders(headers);
    Etag etag = HttpExtensions.etagHeaderToEtag(headers.get("ETag"));
    JsonDocumentMetadata result =  new JsonDocumentMetadata();
    result.setEtag(etag);
    result.setKey(docKey);
    result.setLastModified(getLastModifiedDate(headers));
    result.setMetadata(meta);
    result.setMonAuthoritativeInformation(responseStatusCode == HttpStatus.SC_NON_AUTHORITATIVE_INFORMATION);
    return result;
  }

  public static JsonDocument ravenJObjectToJsonDocument(RavenJObject doc) {
    RavenJObject metadata = (RavenJObject) doc.get("@metadata");
    doc.remove("@metadata");
    String key = extract(metadata, "@id", "", String.class);

    Date lastModified = getLastModified(metadata);

    Etag etag = extract(metadata, "@etag", Etag.empty(), Etag.class);
    boolean nai = extract(metadata, "Non-Authoritative-Information", false, Boolean.class);
    JsonDocument jsonDocument = new JsonDocument(doc, MetadataExtensions.filterHeaders(metadata), key, nai, etag, lastModified);
    jsonDocument.setTempIndexScore(metadata == null ? null : metadata.value(Float.class, Constants.TEMPORARY_SCORE_VALUE));

    return jsonDocument;
  }

  public static JsonDocument deserializeJsonDocument(String docKey, RavenJToken responseJson, Map<String, String> headers, int responseStatusCode) {
    RavenJObject jsonData = (RavenJObject) responseJson;

    RavenJObject meta = MetadataExtensions.filterHeaders(headers);
    Etag etag = HttpExtensions.etagHeaderToEtag(headers.get("ETag"));

    return new JsonDocument(jsonData, meta, docKey, responseStatusCode == HttpStatus.SC_NON_AUTHORITATIVE_INFORMATION, etag, getLastModifiedDate(headers));
  }

  public static JsonDocument toJsonDocument(RavenJObject r) {
    return ravenJObjectToJsonDocument(r);
  }

  public static QueryResult toQueryResult(RavenJObject json, Etag etagHeader, String tempRequestTime) {
    QueryResult result = new QueryResult();
    result.setStale(json.value(Boolean.class, "IsStale"));
    result.setIndexTimestamp(json.value(Date.class, "IndexTimestamp"));
    result.setIndexEtag(etagHeader);
    result.setResults(json.value(RavenJArray.class, "Results").values(RavenJObject.class));
    result.setIncludes(json.value(RavenJArray.class, "Includes").values(RavenJObject.class));
    result.setTotalResults(json.value(Integer.class, "TotalResults"));
    result.setIndexName(json.value(String.class, "IndexName"));
    result.setSkippedResults(json.value(Integer.class, "SkippedResults"));


    RavenJObject highlighings = json.value(RavenJObject.class, "Highlightings");
    if (highlighings == null) {
      highlighings = new RavenJObject();
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Map<String, String[]>> readValue = JsonExtensions.getDefaultObjectMapper().readValue(highlighings.toString(), Map.class);
      result.setHighlightings(readValue);
    } catch (IOException e) {
      throw new RuntimeException("Unable to read highlighings info", e);
    }

    if (json.containsKey("NonAuthoritativeInformation")) {
      result.setNonAuthoritativeInformation(json.value(Boolean.class, "NonAuthoritativeInformation"));
    }
    if (json.containsKey("DurationMilliseconds")) {
      result.setDurationMiliseconds(json.value(Long.class, "DurationMilliseconds"));
    }
    if (StringUtils.isNotEmpty(tempRequestTime)) {
      result.setDurationMiliseconds(Long.valueOf(tempRequestTime.replaceAll(",", "").replaceAll(Pattern.quote("."), "")));
    }
    return result;
  }

}
