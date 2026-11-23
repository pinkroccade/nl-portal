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
package nl.nlportal.commonground.authentication.bff

import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.data.redis.serializer.SerializationException

/**
 * Wat de sessiestore in Redis zet en terugleest: alleen tekst, gehele getallen en booleans, elk met een tekenvoorvoegsel. Geen
 * Java-serialisatie (die laat een schrijver in Redis een willekeurige klasse laten instantiëren, ASVS 1.5.2) en geen polymorfe JSON:
 * een ander type wordt bij het opslaan én bij het lezen geweigerd.
 */
class SessieSerializer : RedisSerializer<Any> {
    override fun serialize(waarde: Any?): ByteArray {
        if (waarde == null) return ByteArray(0)
        val (voorvoegsel, tekst) =
            when (waarde) {
                is String -> 's' to waarde
                is Long -> 'l' to waarde.toString()
                is Int -> 'i' to waarde.toString()
                is Boolean -> 'b' to waarde.toString()
                else -> throw SerializationException("Niet-toegestane sessiewaarde van type " + waarde.javaClass.name)
            }
        return (voorvoegsel + tekst).toByteArray(Charsets.UTF_8)
    }

    override fun deserialize(bytes: ByteArray?): Any? {
        if (bytes == null || bytes.isEmpty()) return null
        val tekst = String(bytes, Charsets.UTF_8)
        val inhoud = tekst.substring(1)
        return try {
            when (tekst[0]) {
                's' -> inhoud
                'l' -> inhoud.toLong()
                'i' -> inhoud.toInt()
                'b' -> inhoud.toStrictBooleanOrNull() ?: throw NumberFormatException()
                else -> throw SerializationException("Niet-toegestane sessiewaarde in Redis")
            }
        } catch (e: NumberFormatException) {
            throw SerializationException("Niet-toegestane sessiewaarde in Redis: onleesbaar getal of boolean")
        }
    }

    private fun String.toStrictBooleanOrNull() =
        when (this) {
            "true" -> true
            "false" -> false
            else -> null
        }
}