# Spanner Sink Module

Sink module to write the input data to a specified Spanner table.

## Sink module common parameters

| parameter | optional | type | description |
| --- | --- | --- | --- |
| name | required | String | Step name. specified to be unique in config file. |
| module | required | String | Specified `spanner` |
| input | required | String | Step name whose data you want to write from |
| wait | Array<String\> | optional | If you want to wait for the completion of other steps, assign a step name to wait for completion. |
| parameters | required | Map<String,Object\> | Specify the following individual parameters. |

## Spanner sink module parameters

| parameter             | optional | type | description                                                                                                                                                                                                 |
|-----------------------| --- | --- |-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| projectId             | required | String | Spanner's GCP Project ID that you want to write                                                                                                                                                             |
| instanceId            | required | String | The Instance ID of the Spanner you want to write                                                                                                                                                            |
| databaseId            | required | String | The Database ID of the Spanner you want to write                                                                                                                                                            |
| table                 | required | String | The table name of the Spanner you want to write                                                                                                                                                             |
| mutationOp            | optional | String | One of `INSERT`, `UPDATE`, `INSERT_OR_UPDATE`, `REPLACE`, or `DELETE`. The default is `INSERT_OR_UPDATE`.                                                                                                   |
| failFast              | optional | Boolean | Specify whether to fail the job immediately when the insert fails. Default is true. (Specify false if you don't want to insert a record if there is an existing record with the same key)                   |
| createTable           | optional | Boolean | Specifies whether the specified table should be created automatically if it does not exist. The default is false.                                                                                           |
| keyFields             | optional | Array<String\> | The name of the fields to be the primary key for the table. If `createTable` is true, or if `mutationOp` is DELETE, it is required.                                                                         |
| commitTimestampFields | optional | Array<String\> | Specify the field name if you want to insert a field for which the optional `allow_commit_timestamp` is true.                                                                                                 |
| fields                | optional | Array<String\> | Specified if you want to limit the columns to be written. The default is all columns. If the following `exclude` is set to true, the field specified here will be removed.                                  |
| exclude               | optional | Boolean | Set to true if you want to remove the fields specified in fields. The default is false.                                                                                                                     |
| maskFields            | optional | Array<String\> | If the schema of the table is fixed and the fields cannot be restricted, the value of the field specified here will be replaced by NULL or a fixed value per type (e.g. 0 for an integer).                  |
| nodeCount             | optional | Integer | Specify the number of instances if you want to change the number of instances before inserting a record into Spanner. After insertion, the number of instances will revert to the number before the change. |
| revertNodeCount       | optional | Integer | Specify if you want the number of Spanner instances to be the specified number after the insertion is finished. (Effective only when `nodeCount` is specified)                                              |
| rebalancingMinite     | optional | Integer | When you specify `nodeCount`, specify the minutes to wait after the number of instances change. default is zero.                                                                                            |
| emulator              | optional | Boolean | If you want to destination the local Spanner Emulator, you must run it in DirectRunner.                                                                                                                     |

## Related example config files

* [BigQuery to Cloud Spanner](../../../../examples/bigquery-to-spanner.json)
* [Cloud Spanner to Cloud Spanner(Insert)](../../../../examples/spanner-to-spanner.json)
* [Cloud Spanner to Cloud Spanner(Delete)](../../../../examples/spanner-to-spanner-delete.json)
* [Cloud Storage(Avro) to Cloud Spanner](../../../../examples/avro-to-spanner.json)
* [AWS S3(Avro) to Cloud Spanner](../../../../examples/aws-avro-to-spanner.json)
* [Cloud Storage(Spanner Backup) to Spanner](../../../../examples/import-spanner-backup.json)
* [SetOperation: Replace Spanner Table](../../../../examples/setoperation-replace-spanner.json)
* [Cloud PubSub(Avro) to Cloud Spanner](../../../../examples/pubsub-avro-to-spanner.json)
