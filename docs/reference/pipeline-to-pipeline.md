---
mapped_pages:
  - https://www.elastic.co/guide/en/logstash/current/pipeline-to-pipeline.html
---

# Pipeline-to-pipeline communication [pipeline-to-pipeline]

When using the multiple pipeline feature of Logstash, you may want to connect multiple pipelines within the same Logstash instance. This configuration can be useful to isolate the execution of these pipelines, as well as to help break-up the logic of complex pipelines. The `pipeline` input/output enables a number of advanced architectural patterns discussed later in this document.

If you need to set up communication *between* Logstash instances, use either [Logstash-to-Logstash](/reference/logstash-to-logstash-communications.md) communications, or an intermediary queue, such as Kafka or Redis.

::::{tip}
Persistent queues (PQs) can help keep data moving through pipelines. See [PQs and pipeline-to-pipeline communication](/reference/persistent-queues.md#pq-pline-pline) to learn how PQs can enhance your pipeline-to-pipeline communication strategy.
::::


## Configuration overview [pipeline-to-pipeline-overview]

Use the `pipeline` input and `pipeline` output to connect two pipelines running within the same Logstash instance. These inputs use a client-server approach, where the `pipeline` input registers a virtual address that a `pipeline` output can connect to.

1. Create a *downstream* pipeline that listens for events on a virtual address.
2. Create an *upstream* pipeline that produces events, sending them through a `pipeline` output to one or more virtual addresses.

Here is a simple example of this configuration.

```yaml
# config/pipelines.yml
- pipeline.id: upstream
  config.string: input { stdin {} } output { pipeline { send_to => [myVirtualAddress] } }
- pipeline.id: downstream
  config.string: input { pipeline { address => myVirtualAddress } }
```

### How it works [how-pipeline-to-pipeline-works]

The `pipeline` input acts as a virtual server listening on a single virtual address in the local process. Only `pipeline` outputs running on the same local Logstash can send events to this address. Pipeline `outputs` can send events to a list of virtual addresses. A `pipeline` output will be blocked if the downstream pipeline is blocked or unavailable.

When events are sent across pipelines, their data is fully copied. Modifications to an event in a downstream pipeline do not affect that event in any upstream pipelines.

The `pipeline` plugin may be the most efficient way to communicate between pipelines, but it still incurs a performance cost. Logstash must duplicate each event in full on the Java heap for each downstream pipeline. Using this feature may affect the heap memory utilization of Logstash.


### Delivery guarantees [delivery-guarantees]

In its standard configuration the `pipeline` input/output has at-least-once delivery guarantees. The output will be blocked if the address is blocked or unavailable.

By default, the `ensure_delivery` option on the `pipeline` output is set to `true.` If you change the `ensure_delivery` flag to `false`, an *unavailable* downstream pipeline causes the sent message to be discarded. Note that a pipeline is considered unavailable only when it is starting up or reloading, not when any of the plugins it may contain are blocked. A *blocked* downstream pipeline blocks the sending output/pipeline regardless of the value of the `ensure_delivery` flag. Use `ensure_delivery => false` when you want the ability to temporarily disable a downstream pipeline without blocking any upstream pipelines sending to it.

These delivery guarantees also inform the shutdown behavior of this feature. When performing a pipeline reload, changes will be made immediately as the user requests, even if that means removing a downstream pipeline receiving events from an upstream pipeline. This will cause the upstream pipeline to block. You must restore the downstream pipeline to cleanly shut down Logstash. You may issue a force kill, but inflight events may be lost unless the persistent queue is enabled for that pipeline.


### Avoid cycles [avoid-cycles]

When you connect pipelines, keep the data flowing in one direction. Looping data or connecting the pipelines into a cyclic graph can cause problems. Logstash waits for each pipeline’s work to complete before shutting down. Pipeline loops can prevent Logstash from shutting down cleanly.



## Architectural patterns [architectural-patterns]

You can use the `pipeline` input and output to better organize code, streamline control flow, and isolate the performance of complex configurations. There are infinite ways to connect pipelines. The ones presented here offer some ideas.

* [The distributor pattern](#distributor-pattern)
* [The output isolator pattern](#output-isolator-pattern)
* [The forked path pattern](#forked-path-pattern)
* [The collector pattern](#collector-pattern)

::::{note}
These examples use `config.string` to illustrate the flows. You can also use configuration files for pipeline-to-pipeline communication.
::::


### The distributor pattern [distributor-pattern]

You can use the distributor pattern in situations where there are multiple types of data coming through a single input, each with its own complex set of processing rules. With the distributor pattern one pipeline is used to route data to other pipelines based on type. Each type is routed to a pipeline with only the logic for handling that type. In this way each type’s logic can be isolated.

As an example, in many organizations a single beats input may be used to receive traffic from a variety of sources, each with its own processing logic. A common way to deal with this type of data is to have a number of `if` conditions separating the traffic and processing each type differently. This approach can quickly get messy when configs are long and complex.

Here is an example distributor pattern configuration.

```yaml
# config/pipelines.yml
- pipeline.id: beats-server
  config.string: |
    input { beats { port => 5044 } }
    output {
        if [type] == apache {
          pipeline { send_to => weblogs }
        } else if [type] == system {
          pipeline { send_to => syslog }
        } else {
          pipeline { send_to => fallback }
        }
    }
- pipeline.id: weblog-processing
  config.string: |
    input { pipeline { address => weblogs } }
    filter {
       # Weblog filter statements here...
    }
    output {
      elasticsearch { hosts => [es_cluster_a_host] }
    }
- pipeline.id: syslog-processing
  config.string: |
    input { pipeline { address => syslog } }
    filter {
       # Syslog filter statements here...
    }
    output {
      elasticsearch { hosts => [es_cluster_b_host] }
    }
- pipeline.id: fallback-processing
    config.string: |
    input { pipeline { address => fallback } }
    output { elasticsearch { hosts => [es_cluster_b_host] } }
```

Notice how following the flow of data is a simple due to the fact that each pipeline only works on a single specific task.


### The output isolator pattern [output-isolator-pattern]

You can use the output isolator pattern to prevent Logstash from becoming blocked if one of multiple outputs experiences a temporary failure. Logstash, by default, is blocked when any single output is down. This behavior is important in guaranteeing at-least-once delivery of data.

For example, a server might be configured to send log data to both Elasticsearch and an HTTP endpoint. The HTTP endpoint might be frequently unavailable due to regular service or other reasons. In this scenario, data would be paused from sending to Elasticsearch any time the HTTP endpoint is down.

Using the output isolator pattern and persistent queues, we can continue sending to Elasticsearch, even when one output is down.

Here is an example of this scenario using the output isolator pattern.

```yaml
# config/pipelines.yml
- pipeline.id: intake
  config.string: |
    input { beats { port => 5044 } }
    output { pipeline { send_to => [es, http] } }
- pipeline.id: buffered-es
  queue.type: persisted
  config.string: |
    input { pipeline { address => es } }
    output { elasticsearch { } }
- pipeline.id: buffered-http
  queue.type: persisted
  config.string: |
    input { pipeline { address => http } }
    output { http { } }
```

In this architecture, each output has its own queue with its own tuning and settings. Note that this approach uses up to twice as much disk space and incurs three times as much serialization/deserialization cost as a single pipeline.

If any of the persistent queues of the downstream pipelines (in the example above, `buffered-es` and `buffered-http`) become full, both outputs will stop.


### The forked path pattern [forked-path-pattern]

You can use the forked path pattern for situations where a single event must be processed more than once according to different sets of rules. Before the `pipeline` input and output were available, this need was commonly addressed through creative use of the `clone` filter and `if/else` rules.

Let’s imagine a use case where we receive data and index the full event in our own systems, but publish a redacted version of the data to a partner’s S3 bucket. We might use the output isolator pattern described above to decouple our writes to either system. The distinguishing feature of the forked path pattern is the existence of additional rules in the downstream pipelines.

Here is an example of the forked path configuration.

```yaml
# config/pipelines.yml
- pipeline.id: intake
  queue.type: persisted
  config.string: |
    input { beats { port => 5044 } }
    output { pipeline { send_to => ["internal-es", "partner-s3"] } }
- pipeline.id: buffered-es
  queue.type: persisted
  config.string: |
    input { pipeline { address => "internal-es" } }
    # Index the full event
    output { elasticsearch { } }
- pipeline.id: partner
  queue.type: persisted
  config.string: |
    input { pipeline { address => "partner-s3" } }
    filter {
      # Remove the sensitive data
      mutate { remove_field => 'sensitive-data' }
    }
    output { s3 { } } # Output to partner's bucket
```


### The collector pattern [collector-pattern]

You can use the collector pattern when you want to define a common set of outputs and pre-output filters that many disparate pipelines might use. This pattern is the opposite of the distributor pattern. In this pattern many pipelines flow in to a single pipeline where they share outputs and processing. This pattern simplifies configuration at the cost of reducing isolation, since all data is sent through a single pipeline.

Here is an example of the collector pattern.

```yaml
# config/pipelines.yml
- pipeline.id: beats
  config.string: |
    input { beats { port => 5044 } }
    output { pipeline { send_to => [commonOut] } }
- pipeline.id: kafka
  config.string: |
    input { kafka { ... } }
    output { pipeline { send_to => [commonOut] } }
- pipeline.id: partner
  # This common pipeline enforces the same logic whether data comes from Kafka or Beats
  config.string: |
    input { pipeline { address => commonOut } }
    filter {
      # Always remove sensitive data from all input sources
      mutate { remove_field => 'sensitive-data' }
    }
    output { elasticsearch { } }
```



