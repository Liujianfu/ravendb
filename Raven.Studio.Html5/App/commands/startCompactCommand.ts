import commandBase = require("commands/commandBase");
import database = require("models/database");
import appUrl = require("common/appUrl");
import deleteDocumentCommand = require("commands/deleteDocumentCommand");
import monitorCompactCommand = require("commands/monitorCompactCommand");

class startCompactCommand extends commandBase {

    private db: database = appUrl.getSystemDatabase();

    constructor(private dbToCompact: string, private updateCompactStatus: (compactStatusDto) => void) {
        super();
    }

    execute(): JQueryPromise<any> {

        var result = $.Deferred();

        new deleteDocumentCommand('Raven/Database/Compact/Status/' + this.dbToCompact, this.db)
            .execute()
            .fail((response: JQueryXHR) => {
                this.reportError("Failed to delete compact status document!", response.responseText, response.statusText);
                result.reject();
            })
            .done(_=> {
                var url = '/admin/compact' + this.urlEncodeArgs({ database: this.dbToCompact });
                this.post(url, null, this.db)
                    .fail((response: JQueryXHR) => {
                        this.reportError("Failed to compact database!", response.responseText, response.statusText);
                        this.logError(response, result);
                    })
                    .done(() => new monitorCompactCommand(result, this.dbToCompact, this.updateCompactStatus).execute());
            });

        return result;
    }

    private logError(response: JQueryXHR, result: JQueryDeferred<any>) {
        var r = JSON.parse(response.responseText);
        var compactStatus: compactStatusDto = { Messages: [r.Error], State: "Faulted" };
        this.updateCompactStatus(compactStatus);
        result.reject();
    }
}

export = startCompactCommand;