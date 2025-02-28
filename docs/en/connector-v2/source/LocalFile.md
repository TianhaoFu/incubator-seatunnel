# LocalFile

> Local file source connector

## Description

Read data from local file system.

## Options

| name   | type   | required | default value |
|--------|--------|----------|---------------|
| path   | string | yes      | -             |
| type   | string | yes      | -             |
| schema | config | no       | -             |

### path [string]

The source file path.

### type [string]

File type, supported as the following file types:

`text` `csv` `parquet` `orc` `json`

If you assign file type to `json`, you should also assign schema option to tell connector how to parse data to the row you want.

For example:

upstream data is the following:

```json

{"code":  200, "data":  "get success", "success":  true}

```

you should assign schema as the following:

```hocon

schema {
    fields {
        code = int
        data = string
        success = boolean
    }
}

```

connector will generate data as the following:

| code | data        | success |
|------|-------------|---------|
| 200  | get success | true    |

If you assign file type to `parquet` `orc`, schema option not required, connector can find the schema of upstream data automatically.

If you assign file type to `text` `csv`, schema option not supported temporarily, but the subsequent features will support.

Now connector will treat the upstream data as the following:

| lines                             |
|-----------------------------------|
| The content of every line in file |

### schema [config]

The schema information of upstream data.

## Example

```hocon

LocalFile {
  path = "/apps/hive/demo/student"
  type = "parquet"
}

```

```hocon

LocalFile {
  schema {
    fields {
      name = string
      age = int
    }
  }
  path = "/apps/hive/demo/student"
  type = "json"
}

```