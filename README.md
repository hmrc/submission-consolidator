
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
 
 **Path:** `/form`
 
 **Request Body:** 
 
 ```json
 {
   "submissionRef": "ABC1-DEF2-HIJ6",
     "formId": "some-form-id",
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
 |400|REQUEST_VALIDATION_FAILED|Request body failed validation|/formId|Is is required|
 |400|REQUEST_VALIDATION_FAILED|Request body failed validation|/templateId|Is required|
 |400|REQUEST_VALIDATION_FAILED|Request body failed validation|/customerId|Is required|
 |400|REQUEST_VALIDATION_FAILED|Request body failed validation|/submissionTimestamp|Is required|
 |400|REQUEST_VALIDATION_FAILED|Request body failed validation|/submissionTimestamp|Must confirm to ISO-8601 date-time format YYYY-MM-DD'T'HH:mm:ssZ|
 |400|REQUEST_VALIDATION_FAILED|Request body failed validation|/formData(0)/id|Is required|
 |400 |REQUEST_VALIDATION_FAILED|Request body failed validation|/formData(0)/value|Is required|
 |503|SERVICE_UNAVAILABLE|The service is not available due to downstream services (eg Mongo DB)| | |

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
