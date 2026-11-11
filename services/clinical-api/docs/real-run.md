# Real run evidence (synthetic data)

clinical-api 4.1.2 jar on :23082 against ad-hoc containers on 127.0.0.1 ports 23xxx (Postgres 23432, Kafka 23092, Redis 23379, RabbitMQ 23672, Elasticsearch 23200, Cassandra 23042, LocalStack 23566). Produced to `alerts.detected`: one CRITICAL alert (correlationId corr-real-1), the same alert again (duplicate), and one poison record.

## 1. Postgres: one alert row (duplicate was a no-op), outbox rows all published
```
                  id                  | patient_id |   type    | severity | metric | status | version 
--------------------------------------+------------+-----------+----------+--------+--------+---------
 7c1d2b34-5e6f-4a8b-9c0d-1e2f3a4b5c6d | P-0001     | THRESHOLD | CRITICAL | SPO2   | OPEN   |       0
(1 row)

   event_type   | published 
----------------+-----------
 ALERT_ESCALATE | t
 ALERT_INDEX    | t
 ALERT_NOTIFY   | t
(3 rows)

```
## 2. Elasticsearch hit via GET /api/v1/alerts?q=oxygen
```
{"items":[{"alertId":"7c1d2b34-5e6f-4a8b-9c0d-1e2f3a4b5c6d","patientId":"P-0001","type":"THRESHOLD","severity":"CRITICAL","metric":"SPO2","value":88.0,"score":null,"message":"SpO2 critically low (88 percent), oxygen saturation below 90","windowStart":"2026-11-11T20:15:00Z","windowEnd":"2026-11-11T20:16:00Z","detectedAt":"2026-11-11T20:16:02.010Z","status":"OPEN","acknowledgedBy":null,"acknowledgedAt":null,"acknowledgementNote":null,"version":0}],"page":0,"size":20,"totalElements":1,"totalPages":1}
```
## 3. RabbitMQ message on notify.all (routing key alert.critical; one message although the alert was produced twice; the correlationId AMQP header is asserted in AlertPipelineIT, rabbitmqadmin does not print properties)
```
+----------------+---------------+---------------+--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+---------------+------------------+-------------+
|  routing_key   |   exchange    | message_count |                                                                                                                                                                                                                        payload                                                                                                                                                                                                                         | payload_bytes | payload_encoding | redelivered |
+----------------+---------------+---------------+--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+---------------+------------------+-------------+
| alert.critical | alerts.notify | 0             | {"alertId":"7c1d2b34-5e6f-4a8b-9c0d-1e2f3a4b5c6d","patientId":"P-0001","type":"THRESHOLD","severity":"CRITICAL","metric":"SPO2","value":88.0,"score":null,"message":"SpO2 critically low (88 percent), oxygen saturation below 90","windowStart":"2026-11-11T20:15:00Z","windowEnd":"2026-11-11T20:16:00Z","detectedAt":"2026-11-11T20:16:02.010Z","status":"OPEN","acknowledgedBy":null,"acknowledgedAt":null,"acknowledgementNote":null,"version":0} | 438           | string           | False       |
+----------------+---------------+---------------+--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+---------------+------------------+-------------+
```
## 4. SQS message in alert-escalations (LocalStack)
```
{
    "Messages": [
        {
            "MessageId": "b71627bc-baf9-4ac1-b8aa-0daf4e0ef066",
            "ReceiptHandle": "MjBhOTY0ZTAtZjdiOS00NGUwLWI0ZmQtY2NjYjkyY2ZhMzIyIGFybjphd3M6c3FzOnVzLWVhc3QtMTowMDAwMDAwMDAwMDA6YWxlcnQtZXNjYWxhdGlvbnMgYjcxNjI3YmMtYmFmOS00YWMxLWI4YWEtMGRhZjRlMGVmMDY2IDE3OTQ0MzgzMjMuMTAxMzg1",
            "MD5OfBody": "88d4588bc4bee12ffb0390586eb36035",
            "Body": "{\"alertId\":\"7c1d2b34-5e6f-4a8b-9c0d-1e2f3a4b5c6d\",\"patientId\":\"P-0001\",\"type\":\"THRESHOLD\",\"severity\":\"CRITICAL\",\"metric\":\"SPO2\",\"value\":88.0,\"score\":null,\"message\":\"SpO2 critically low (88 percent), oxygen saturation below 90\",\"windowStart\":\"2026-11-11T20:15:00Z\",\"windowEnd\":\"2026-11-11T20:16:00Z\",\"detectedAt\":\"2026-11-11T20:16:02.010Z\",\"status\":\"OPEN\",\"acknowledgedBy\":null,\"acknowledgedAt\":null,\"acknowledgementNote\":null,\"version\":0}",
            "MD5OfMessageAttributes": "7c8a0797d14a2e149c9fd87fdd3c0941",
            "MessageAttributes": {
                "correlationId": {
                    "StringValue": "corr-real-1",
                    "DataType": "String"
                }
            }
        }
    ]
}
```
## 5. DLQ record for the poison message (original bytes + headers; stack-trace header omitted here for length)
```
topic: alerts.detected.dlq   key: P-0001   value (original bytes): {this is not json
headers: correlationId=corr-poison
         originalTopic=alerts.detected
         error=PoisonMessageException: Malformed AlertEvent JSON: Unexpected character ('t' (code 116)): was expecting double-quote to start property name
         kafka_dlt-exception-cause-fqcn=io.vitalstream.clinical.infrastructure.kafka.PoisonMessageException
         kafka_dlt-original-topic=alerts.detected, kafka_dlt-original-consumer-group=alert-consumer (plus partition/offset/timestamp)
```
Dead-lettered immediately (no retries) as it is a poison record.

## 6. SSE event received with curl -N on /api/v1/alerts/stream
```
:connected

id:7c1d2b34-5e6f-4a8b-9c0d-1e2f3a4b5c6d:0
event:alert
data:{"alertId":"7c1d2b34-5e6f-4a8b-9c0d-1e2f3a4b5c6d","patientId":"P-0001","type":"THRESHOLD","severity":"CRITICAL","metric":"SPO2","value":88.0,"score":null,"message":"SpO2 critically low (88 percent), oxygen saturation below 90","windowStart":"2026-11-11T20:15:00Z","windowEnd":"2026-11-11T20:16:00Z","detectedAt":"2026-11-11T20:16:02.010Z","status":"OPEN","acknowledgedBy":null,"acknowledgedAt":null,"acknowledgementNote":null,"version":0}

:keep-alive

:keep-alive

```
## 7. Ack: 200, then second ack 409 (stale-version 409 via the optimistic lock is covered by AlertsApiIT)
```
$ curl -i -X POST /api/v1/alerts/7c1d2b34-5e6f-4a8b-9c0d-1e2f3a4b5c6d/ack -d {"by":"dr.cohen","note":"at bedside"}
HTTP/1.1 200 
{"alertId":"7c1d2b34-5e6f-4a8b-9c0d-1e2f3a4b5c6d","patientId":"P-0001","type":"THRESHOLD","severity":"CRITICAL","metric":"SPO2","value":88.0,"score":null,"message":"SpO2 critically low (88 percent), oxygen saturation below 90","windowStart":"2026-11-11T20:15:00Z","windowEnd":"2026-11-11T20:16:00Z","detectedAt":"2026-11-11T20:16:02.010Z","status":"ACKNOWLEDGED","acknowledgedBy":"dr.cohen","acknowledgedAt":"2026-11-11T23:05:34.354685Z","acknowledgementNote":"at bedside","version":1}
$ second ack
HTTP/1.1 409 
Content-Type: application/problem+json
{"detail":"Alert cannot move from ACKNOWLEDGED to ACKNOWLEDGED","instance":"/api/v1/alerts/7c1d2b34-5e6f-4a8b-9c0d-1e2f3a4b5c6d/ack","status":409,"title":"Conflict","correlationId":"3d6e8d8c-a64b-4e65-9f52-6a25d78f74ca"}
```
## 8. Patient with latest vitals (Redis) and open alerts
```
{"id":"P-0001","name":"Avi Cohen","bed":"B-01","ward":"ICU","latestVitals":{"SPO2":{"value":88.0,"unit":"%","observedAt":"2026-11-11T20:15:30.123Z"}},"openAlerts":0}
```
## 9. Metrics
```
alerts_consumed_total{outcome="duplicate",service="clinical-api"} 1.0
alerts_consumed_total{outcome="persisted",service="clinical-api"} 1.0
alerts_dlq_total{service="clinical-api"} 1.0
notifications_sent_total{channel="rabbitmq",service="clinical-api"} 1.0
notifications_sent_total{channel="sqs",service="clinical-api"} 1.0
outbox_lag_seconds{service="clinical-api"} 0.19108
vitalstream_alerts_raised_total{service="clinical-api",severity="CRITICAL",type="THRESHOLD"} 1.0
```
## 10. Log sample (JSON, correlationId propagated Kafka header -> outbox -> RabbitMQ)
```
{"timestamp":"2026-11-12T01:05:02.513371+02:00","message":"Alert 7c1d2b34-5e6f-4a8b-9c0d-1e2f3a4b5c6d for patient 26043831372c: PERSISTED","logger_name":"io.vitalstream.clinical.application.AlertIngestService","thread_name":"alert-consumer-1-C-1","level":"INFO","rootLevel":"INFO","topic":"alerts.detected","correlationId":"corr-real-1","partition":"2","alertId":"7c1d2b34-5e6f-4a8b-9c0d-1e2f3a4b5c6d","offset":"0","patientRef":"26043831372c","service":"clinical-api"}
{"timestamp":"2026-11-12T01:05:02.948947+02:00","message":"Outbox ALERT_INDEX delivered for alert 7c1d2b34-5e6f-4a8b-9c0d-1e2f3a4b5c6d","logger_name":"io.vitalstream.clinical.application.OutboxRelay","thread_name":"scheduling-1","level":"INFO","rootLevel":"INFO","correlationId":"corr-real-1","service":"clinical-api"}
```
