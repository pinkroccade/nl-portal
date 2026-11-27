/*
 * Copyright 2026 PinkRoccade Local Government B.V.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.nlportal.core.modulegate

import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.util.ClassUtils

/** Where an endpoint is registered at the gate: the core, a module that is not on the gate yet, or a module with a flag. */
sealed interface EndpointToewijzing {
    data object Kern : EndpointToewijzing

    data class Overgang(
        val module: String,
    ) : EndpointToewijzing

    data class Module(
        val module: ModuleFlag,
    ) : EndpointToewijzing
}

/**
 * The registration of the endpoints at the gate: which controller (by class) belongs to which module, read from
 * [ModuleEndpoint] on the controller beans. A class without the annotation has no assignment and is denied.
 */
class ModuleEndpoints(
    private val beanFactory: ListableBeanFactory,
    private val modules: List<ModuleFlag>,
) {
    /** The registered controller classes with their assignment, resolved lazily so no bean is created early. */
    val registraties: Map<Class<*>, EndpointToewijzing> by lazy {
        val perModule = modules.associateBy { it.module }
        beanFactory
            .getBeanNamesForAnnotation(ModuleEndpoint::class.java)
            .mapNotNull { naam -> beanFactory.getType(naam)?.let { ClassUtils.getUserClass(it) } }
            .associateWith { type ->
                val annotatie = checkNotNull(AnnotationUtils.findAnnotation(type, ModuleEndpoint::class.java))
                toewijzing(type, annotatie, perModule)
            }
    }

    /** The assignment of the controller [type], or `null` when it is not registered: deny by default. */
    fun van(type: Class<*>): EndpointToewijzing? = registraties[ClassUtils.getUserClass(type)]

    /** The assignment of the module with [naam] (for a nested field registered with [ModuleVeld]); fails when it has no [ModuleFlag] bean. */
    fun moduleToewijzing(naam: String): EndpointToewijzing.Module =
        EndpointToewijzing.Module(
            checkNotNull(modules.firstOrNull { it.module == naam }) { "@ModuleVeld noemt module '$naam', maar er is geen ModuleFlag-bean met die naam" },
        )

    /** Fails when a registration names a module without a registered [ModuleFlag]; call once at startup. */
    fun controleer() {
        registraties
    }

    private fun toewijzing(
        type: Class<*>,
        annotatie: ModuleEndpoint,
        perModule: Map<String, ModuleFlag>,
    ): EndpointToewijzing =
        when {
            annotatie.module == ModuleEndpoint.KERN -> {
                check(!annotatie.overgang) { "${type.name}: de kern kent geen overgang" }
                EndpointToewijzing.Kern
            }

            annotatie.overgang -> {
                EndpointToewijzing.Overgang(annotatie.module)
            }

            else -> {
                EndpointToewijzing.Module(
                    checkNotNull(perModule[annotatie.module]) {
                        "${type.name}: @ModuleEndpoint noemt module '${annotatie.module}', maar er is geen ModuleFlag-bean met die naam"
                    },
                )
            }
        }
}