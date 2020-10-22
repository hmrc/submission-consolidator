
# Submission Collector and Consolidator Service

This service collects form submission data (gform data) and stores it into a mongo database. A scheduled job (also part of this service) will then run at pre-configured intervals and consolidate all the data from the last run into a single file and publish the file to a DMS queue destination.

## Running in DEV mode

To start the service locally in DEV mode, execute the following command

```$ sbt run ```

To run locally using Service Manager

```sm --start SUBMISSION_CONSOLIDATOR -f```

## Collector - API Details

### Add Form

Accepts form submission data and stores it in the mongodb collection (submission-consolidator).

 **Method:** `POST`
 
 **Path:** `/submission-consolidator/form`
 
 **Request Body:** 
 
 ```json
 {
     "submissionRef": "ABC1-DEF2-HIJ6",
     "projectId": "some-project-id",
     "templateId": "some-template-id",
     "customerId": "some-customer-id",
     "submissionTimestamp": "2020-01-01T00:00:00Z",
     "formData": [
         {
             "id": "1",
             "value": "value1"
         }
     ]
 }
 ```

 **Responses**
 
 |Status|Code|Message|Field Path|Field Message|
 |------|----|-------|----------|-------------|
 |200| | | | |
 |400|REQUEST_VALIDATION_FAILED|Request body failed validation|/submissionReference|Is required|
 |400|REQUEST_VALIDATION_FAILED|Request body failed validation|/submissionReference|Must confirm to the format XXXX-XXXX-XXXX, where X is a upper-case alphabet or a number|
 |400 |DUPLICATE_SUBMISSION_REFERENCE|Submission reference must be unique| | |
 |400|REQUEST_VALIDATION_FAILED|Request body failed validation|/projectId|Is is required|
 |400|REQUEST_VALIDATION_FAILED|Request body failed validation|/templateId|Is required|
 |400|REQUEST_VALIDATION_FAILED|Request body failed validation|/customerId|Is required|
 |400|REQUEST_VALIDATION_FAILED|Request body failed validation|/submissionTimestamp|Is required|
 |400|REQUEST_VALIDATION_FAILED|Request body failed validation|/submissionTimestamp|Must confirm to ISO-8601 date-time format YYYY-MM-DD'T'HH:mm:ssZ|
 |400|REQUEST_VALIDATION_FAILED|Request body failed validation|/formData(0)/id|Is required|
 |400 |REQUEST_VALIDATION_FAILED|Request body failed validation|/formData(0)/value|Is required|
 |503|SERVICE_UNAVAILABLE|The service is not available due to downstream services (eg Mongo DB)| | |
 
### Manual Consolidation

Trigger a manual consolidation of forms, for a given consolidator job id and date range. The list of allowed `consolidatorJobId` values are available in the environment conf files (e.g: app-config-production/submission-consolidator.yaml), under the consolidator-jobs property (the id attribute).

`startDate` and `endDate` specify the date ranges for the form submission date. Both are specified in `YYYY-MM-DD` format. `startDate` is set to the start of the day (00:00:00) and `endDate` is set to the end of the day (23:59:59)

**Method:** `POST`
 
**Path:** `/submission-consolidator/form/:consolidatorJobId/:startDate/:endDate`

**Responses**

|Status|Code|Message|
|------|----|-------|
|204| | | | |
|409| CONSOLIDATOR_JOB_ALREADY_IN_PROGRESS | Consolidator job already in progress [consolidatorJobId=XXX] |
|400| INVALID_CONSOLIDATOR_JOB_ID | Invalid consolidator job id XXX. No actor found |
|500| MANUAL_CONSOLIDATION_FAILED | Failed to consolidate forms [consolidatorJobId=XXX, error=XXX] |

## Consolidator and Submitter

Consolidator jobs are configured in application.conf and scheduled to run periodically based on a cron expression. The jobs consolidate all the forms for the given `projectId` and submits them as JSON line file to the configured DMS queue (indentified by `classificationType` and `businessArea`)

```
consolidator-jobs = [
    {
        id = "job-id"
        params = {
            projectId = "test-project"
            classificationType = "classification-type"
            businessArea = "business-area"
            format = "jsonl"
        }
        # run every 30 seconds
        cron = "*/30 * * ? * *"
    }
]
```

The `projectId` param defines filter used when fetching form data from the forms collection. Supported formats are `jsonl` and `csv` (default if not specified is `jsonl`). `classificationType` and `businessArea` determine the DMS queue destination. On application startup, the configured jobs are scheduled as quartz jobs, running at the frequency represented by the cron expression.

When the job runs, the following happens in order

1. Fetch the last object id from the previous run (from the consolidator_job_datas collection)
1. Fetch all the form data from the forms collection as a stream, where object id is greater than last object id and creation time is NOW - 5s (to avoid skipping of rows due to ObjectId ordering issues)
1. Transform each form to a JSON line (whem format=jsonl) or CSV (when format=csv)
1. Add the generate line to a report output file. Optional add form data id headers line if format is `csv`
1. When the size of the current  report output file exceeds the max per item size (1 MB), close the current and create a new one
1. Create an envelope
1. Attach metadata xml to the envelope
1. Attach the generated report files to the envelope
1. Route the envelope to a specified DMS queue, indentified by `classificationType` and `businessArea`
1. Add the job details in consolidator_job_datas collection ( if successful lastObjectId and envelopeId, if failed then error message, start and end timestamps)

Note: When the total size of all report files exceed the max file size (25 MB), add the remainder of the files to a new envelope and submit.

All environments are configured to run with multiple instances of the application, for scalability and resiliency. As the scheduler is local to an application, the same jobs are initialized and triggered from all running instances. To prevent duplicate jobs getting triggered, a locking mechanism is used, via the `mongo-lock` library. The project id is used as the lock id. This ensures only one of the instances gets the lock for the project, while other instances skip the job.

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
