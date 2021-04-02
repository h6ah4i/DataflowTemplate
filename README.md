# Mercari Dataflow Template

The Mercari Dataflow Template allows you to run various pipelines without writing programs by simply defining a configuration file.

Mercari Dataflow Template is implemented as a [FlexTemplate](https://cloud.google.com/dataflow/docs/guides/templates/using-flex-templates) for [Cloud Dataflow](https://cloud.google.com/dataflow). Pipelines are assembled based on the defined configuration file and can be executed as Cloud Dataflow Jobs.

See the [Document](docs/README.md) for usage

## Usage Example

Write the following json file and upload it to GCS (Suppose you upload it to gs://example/config.json).

This configuration file stores the BigQuery query results in the table specified by Spanner.

```json
{
  "sources": [
    {
      "name": "bigquery",
      "module": "bigquery",
      "parameters": {
        "query": "SELECT * FROM `myproject.mydataset.mytable`"
      }
    }
  ],
  "sinks": [
    {
      "name": "spanner",
      "module": "spanner",
      "input": "bigquery",
      "parameters": {
        "projectId": "myproject",
        "instanceId": "myinstance",
        "databaseId": "mydatabase",
        "table": "mytable"
      }
    }
  ]
}
```

Assuming you have deployed the Mercari Dataflow Template to gs://example/template, run the following command.

```sh
gcloud dataflow flex-template run bigquery-to-spanner \
  --template-file-gcs-location=gs://example/template \
  --parameters=config=gs://example/config.json
```

The Dataflow job will be started, and you can check the execution status of the job in the console screen.

<img src="https://raw.githubusercontent.com/mercari/DataflowTemplate/master/docs/images/bigquery-to-spanner.png">


## Deploy Template

Mercari Dataflow Template is used as FlexTemplate.
Therefore, the Mercari Dataflow Template should be deployed according to the FlexTemplate creation steps.

### Requirements

* Java 11
* [Maven 3](https://maven.apache.org/index.html)
* [gcloud command-line tool](https://cloud.google.com/sdk/gcloud)

### Push Template Container Image to Cloud Container Registry.

The first step is to build the source code and register it as a container image in the [Cloud Container Registry](https://cloud.google.com/container-registry).

The following command will generate a container for FlexTemplate from the source code and upload it to Container Registry.

```sh
mvn clean package -DskipTests -Dimage=gcr.io/{deploy_project}/{template_repo_name}
```

### Upload template file.

The next step is to generate a template file to start a job from the container image and upload it to GCS.

Use the following command to generate a template file that can execute a dataflow job from a container image, and upload it to GCS.

```sh
gcloud dataflow flex-template build gs://{path/to/template_file} \
  --image "gcr.io/{deploy_project}/{template_repo_name}" \
  --sdk-language "JAVA"
```

## Run dataflow job from template file

Run Dataflow Job from the template file.

* gcloud command

You can run template specifying gcs path that uploaded config file.

```sh
gsutil cp config.json gs://{path/to/config.json}

gcloud dataflow flex-template run {job_name} \
  --template-file-gcs-location=gs://{path/to/template_file} \
  --parameters=config=gs://{path/to/config.json}
```

* REST API

You can also run template by [REST API](https://cloud.google.com/dataflow/docs/reference/rest/v1b3/projects.locations.flexTemplates/launch).

```sh
PROJECT_ID=[PROJECT_ID]
REGION=[REGION]
CONFIG="$(cat examples/xxx.json)"

curl -X POST -H "Content-Type: application/json"  -H "Authorization: Bearer $(gcloud auth print-access-token)" "https://dataflow.googleapis.com/v1b3/projects/${PROJECT_ID}/locations/${REGION}/flexTemplates:launch" -d "{
  'launchParameter': {
    'jobName': 'myJobName',
    'containerSpecGcsPath': 'gs://{path/to/template_file}',
    'parameters': {
      'config': '$(echo "$CONFIG")',
      'stagingLocation': 'gs://{path/to/staging}'
    },
    'environment': {
      'tempLocation': 'gs://{path/to/temp}'
    }
  }
}"
```

(The options `tempLocation` and `stagingLocation` are optional. If not specified, a bucket named `dataflow-staging-{region}-{project_no}` will be automatically generated and used)

### Run Template in streaming mode

To run Template in streaming mode, specify `streaming=true` in the parameter.

```sh
gcloud dataflow flex-template run {job_name} \
  --template-file-gcs-location=gs://{path/to/template_file} \
  --parameters=config=gs://{path/to/config.json} \
  --parameters=streaming=true
```

## Deploy Docker image for local pipeline

You can run pipeline locally. This is useful when you want to process small data quickly.

At first, you should register the container for local execution.


```sh
mvn clean package -DskipTests
docker build --tag=gcr.io/{deploy_project}/{repo_name_local} .
gcloud docker --project {deploy_project} -- push gcr.io/{deploy_project}/{repo_name_local}
```

## Run Pipeline locally

```sh
docker run \
  -v ~/.config/gcloud:/mnt/gcloud:ro \
  --rm gcr.io/{deploy_project}/{repo_name_local} \
  --project={project} \
  --config="$(cat config.json)"
```

## Committers

 * Yoichi Nagai ([@orfeon](https://github.com/orfeon))

## Contribution

Please read the CLA carefully before submitting your contribution to Mercari.
Under any circumstances, by submitting your contribution, you are deemed to accept and agree to be bound by the terms and conditions of the CLA.

https://www.mercari.com/cla/

## License

Copyright 2021 Mercari, Inc.

Licensed under the MIT License.