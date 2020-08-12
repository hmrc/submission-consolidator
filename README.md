
# Submission Collector and Consolidator Service

This service collects form submission data (gform data) and stores it into a mongo database. A scheduled job (also part of this service) will then run at pre-configured intervals and consolidate all the data from the last run into a single file and publish the file to a DMS queue destination.

## Running in DEV mode

To start the service locally in DEV mode, execute the following command

```$ sbt run ```

To run locally using Service Manager

```sm --start SUBMISSION_CONSOLIDATOR -f```

## API Details

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
 
### Consolidator

Consolidator jobs are configured in application.conf and scheduled to run periodically based on a cron expression. The jobs consolidate all the forms for the given `projectId` and submits them as JSON line file to the configured DMS queue (indentified by `classificationType` and `businessArea`)

```
consolidator-jobs = [
    {
        id = "job-id"
        params = {
            projectId = "test-project"
            classificationType = "classification-type"
            businessArea = "business-area"
        }
        # run every 30 seconds
        cron = "*/30 * * ? * *"
    }
]
```

The `projectId` param defines filter used when fetching form data from the forms collection. On application startup, the configured jobs are scheduled as quartz jobs, running at the frequency represented by the cron expression.

When the job runs, the following happens in order

1. Create a temp output file, in write mode
1. Fetch the last object id from the previous run (from the consolidator_job_datas collection)
1. Get the forms metadata (count and max-id), where object id is greater than last object id (from step 2)
1. Fetch all the form data from the forms collection in batches, where object id is greater than last object id and less than max-id (from step 2 & 3)
1. Transform each form to a JSON line
1. Add the generate JSON line to the output file (step 1)
1. Create an envelope
1. Attach metadata xml to the envelope
1. Attach the generated JSON lines file to the envelope
1. Routes the envelope to a specified DMS queue, indentified by `classificationType` and `businessArea`

All environments are configured to run with multiple instances of the application, for scalability and resiliency. As the scheduler is local to an application, the same jobs are initialized and triggered from all available instances. To prevent same job from getting triggered simultaniously, a locking mechanism is used, using the `mongo-lock` library. The project id is used as the lock id, which ensure only one of the instances gets the locks and the other instances skip the job due to unavailability of the lock.

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
