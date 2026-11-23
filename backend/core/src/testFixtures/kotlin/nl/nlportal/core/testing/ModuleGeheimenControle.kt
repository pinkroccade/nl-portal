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
package nl.nlportal.core.testing

import nl.nlportal.core.tenancy.NietErfbaar
import nl.nlportal.core.tenancy.TenantModules
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor

/**
 * Bewaakt dat een moduleconfiguratie geen geheim als gewone tekst bevat (epic #499, story 12). Core kan een `apiKey: String` niet van een
 * `client: String` onderscheiden; deze controle loopt over de configuratieklassen van alle bijdragen en meldt elk tekstveld waarvan
 * de naam op een geheim lijkt, tenzij het `@NietErfbaar` is (een credential die geen geheim is, zoals een sleutelpad) of expliciet is
 * toegestaan. De `:app`-test draait hem over alle modules op het klassenpad, zodat een nieuwe module hem niet kan vergeten.
 */
object ModuleGeheimenControle {
    private val VERDACHT = Regex("secret|geheim|token|wachtwoord|password|apikey|api-key|sleutel|key", RegexOption.IGNORE_CASE)

    /**
     * De velden (`<module>.<pad>`, lijsten en maps zonder aanduiding) die tekst zijn en op een geheim lijken. [uitzonderingen] bevat
     * een volledig pad of een eigenschapnaam die geen geheim is, bijvoorbeeld `keyId`.
     */
    fun verdachteVelden(
        modules: TenantModules,
        uitzonderingen: Set<String> = emptySet(),
    ): List<String> = modules.configuraties.flatMap { verdacht(it.klasse, it.naam, uitzonderingen) }

    private fun verdacht(
        klasse: KClass<*>,
        pad: String,
        uitzonderingen: Set<String>,
    ): List<String> =
        klasse.primaryConstructor
            ?.parameters
            .orEmpty()
            .flatMap { parameter ->
                val naam = parameter.name ?: return@flatMap emptyList()
                val veldPad = "$pad.$naam"
                val blad = blad(parameter.type)
                when {
                    blad == String::class -> {
                        if (VERDACHT.containsMatchIn(naam) && parameter.findAnnotation<NietErfbaar>() == null && naam !in uitzonderingen && veldPad !in uitzonderingen) listOf(veldPad) else emptyList()
                    }

                    blad != null && blad.isData -> {
                        verdacht(blad, veldPad, uitzonderingen)
                    }

                    else -> {
                        emptyList()
                    }
                }
            }

    /** De klasse onder lijsten, verzamelingen en maps (de waarde), of het type zelf. */
    private fun blad(type: KType): KClass<*>? {
        val klasse = type.classifier as? KClass<*> ?: return null
        return if (klasse == List::class || klasse == Set::class || klasse == Map::class) {
            type.arguments
                .lastOrNull()
                ?.type
                ?.let(::blad)
        } else {
            klasse
        }
    }
}