package de.fraunhofer.ccit.examples

import org.apache.camel.CamelContext
import org.apache.camel.Exchange
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.model.rest.RestBindingMode
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ConnectorConfiguration {

    @Bean
    fun builder(): RouteBuilder {
        return object : RouteBuilder() {
            override fun configure() {
                //restConfiguration()
                rest("/camel-example-api/")
                    .id("api-route")
                    .consumes("application/json")
                    .post("/bean")
                    .bindingMode(RestBindingMode.json_xml)
                    .type(MyBean::class.java)
                    .to("direct:remoteService")

                from("direct:remoteService")
                    .routeId("direct-route")
                    .tracing()
                    .log(">>> \${body.id}")
                    .log(">>> \${body.name}")
                    .transform().simple("Hello \${in.body.name}")
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
            }
        }
    }
}