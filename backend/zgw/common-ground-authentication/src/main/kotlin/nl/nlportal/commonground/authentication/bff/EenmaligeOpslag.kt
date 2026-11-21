package nl.nlportal.commonground.authentication.bff

import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Kortlevende opslag voor de pre-auth-sessie (de lopende login). Elke waarde heeft een TTL en [neem] geeft hem hooguit
 * één keer terug (replaybescherming). Adapters: Redis (productie, gedeeld tussen replica's) en geheugen (tests).
 */
interface EenmaligeOpslag {
    fun zet(
        sleutel: String,
        waarde: String,
        ttl: Duration,
    ): Mono<Void>

    fun lees(sleutel: String): Mono<String>

    /** Leest en verwijdert in één stap. */
    fun neem(sleutel: String): Mono<String>

    fun verwijder(sleutel: String): Mono<Void>
}

/** Redis: `SET` met TTL en `GETDEL`; geen persistentie en ACL zijn een zaak van de Redis-instantie. */
class RedisEenmaligeOpslag(
    private val redis: ReactiveStringRedisTemplate,
) : EenmaligeOpslag {
    override fun zet(
        sleutel: String,
        waarde: String,
        ttl: Duration,
    ): Mono<Void> = redis.opsForValue().set(sleutel, waarde, ttl).then()

    override fun lees(sleutel: String): Mono<String> = redis.opsForValue().get(sleutel)

    override fun neem(sleutel: String): Mono<String> = redis.opsForValue().getAndDelete(sleutel)

    override fun verwijder(sleutel: String): Mono<Void> = redis.delete(sleutel).then()
}

/** Geheugen met een injecteerbare klok: de adapter voor tests zonder Redis. */
class GeheugenEenmaligeOpslag(
    private val clock: Clock,
) : EenmaligeOpslag {
    private class Item(
        val waarde: String,
        val verloopt: Instant,
    )

    private val items = ConcurrentHashMap<String, Item>()

    override fun zet(
        sleutel: String,
        waarde: String,
        ttl: Duration,
    ): Mono<Void> =
        Mono.fromRunnable {
            items[sleutel] = Item(waarde, clock.instant().plus(ttl))
        }

    override fun lees(sleutel: String): Mono<String> = Mono.defer { Mono.justOrEmpty(geldig(items[sleutel])) }

    override fun neem(sleutel: String): Mono<String> = Mono.defer { Mono.justOrEmpty(geldig(items.remove(sleutel))) }

    override fun verwijder(sleutel: String): Mono<Void> = Mono.fromRunnable { items.remove(sleutel) }

    private fun geldig(item: Item?): String? = item?.takeIf { clock.instant().isBefore(it.verloopt) }?.waarde
}
