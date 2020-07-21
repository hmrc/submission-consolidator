
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

> **Method:** POST
> **Path:** /form
> **Request Body**
> ```json
> {
>   "submissionRef": "ABC1-DEF2-HIJ6",
>     "formId": "some-form-id",
>     "templateId": "some-template-id",
>     "customerId": "some-customer-id",
>     "submissionTimestamp": "2020-01-01T00:00:00Z",
>     "formData": [
>         {
>             "id": "1",
>             "value": "value1"
>         }
>     ]
> }
> ```

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
