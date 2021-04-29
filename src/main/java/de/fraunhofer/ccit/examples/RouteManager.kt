package de.fraunhofer.ccit.examples

import org.apache.camel.CamelContext
import org.apache.camel.model.ModelCamelContext
import org.apache.camel.model.RouteDefinition
import org.apache.camel.spring.boot.ComponentConfigurationPropertiesCommon
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Component

@Component
class RouteManager {
        @Autowired
        private lateinit var ctx: ApplicationContext

    fun listComponents(): List<RouteComponent> {
        val beanNamesForType =
                ctx.getBeanNamesForType(ComponentConfigurationPropertiesCommon::class.java)

        // yes, this is a bit hacky but will do fow now
        return beanNamesForType
                .mapNotNull { name ->
                    val first = name.split("-org")[0]

                    if (first == "camel.component") {
                        null
                    } else {
                        RouteComponent("camel-" + first.split("camel.component.")[1], "")
                    }
                }
    }

    // sort the list
    private val camelContexts: List<CamelContext>
        get() {
            return try {
                val contexts = ctx.getBeansOfType(CamelContext::class.java).values.toMutableList()

                contexts.sortWith(Comparator.comparing { it.name })

                return contexts
            } catch (e: Exception) {
                println("Cannot retrieve the list of Camel contexts." + e)
                emptyList()
            }
        }

    private fun routeDefinitionToObject(cCtx: CamelContext, rd: RouteDefinition): RouteObject {
        return RouteObject(
                rd.id,
                rd.descriptionText,
                "",
                rd.shortName,
                cCtx.name,
                cCtx.uptimeMillis,
                cCtx.routeController.getRouteStatus(rd.id).toString()
        )
    }

    fun getRoutes(): List<RouteObject> {
        val result: MutableList<RouteObject> = ArrayList()
        val camelContexts = camelContexts

        // Create response
        for (cCtx in camelContexts) {
            val mcc = cCtx.adapt(ModelCamelContext::class.java)
            for (rd in mcc.routeDefinitions) {
                result.add(routeDefinitionToObject(cCtx, rd))
            }
        }
        return result
    }
}