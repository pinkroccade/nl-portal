export interface PerfConfig {
  graphqlUrl: string;
  tokenUrl: string;
  clientId: string;
  username: string;
  password: string;
}

type Env = Record<string, string | undefined>;

const zonderSlashAchteraan = (url: string) => url.replace(/\/+$/, "");

function verplicht(env: Env, naam: string): string {
  const waarde = env[naam];
  if (!waarde) {
    throw new Error(`Omgevingsvariabele ${naam} is verplicht (zie performance/README.md)`);
  }
  return waarde;
}

/** Leest de perf-configuratie uit de omgeving; zonder opgave geldt de lokale compose-stack. */
export function loadConfig(env: Env): PerfConfig {
  const backend = zonderSlashAchteraan(env.PERF_BACKEND_URL ?? "http://localhost:8080");
  const keycloak = zonderSlashAchteraan(env.PERF_KEYCLOAK_URL ?? "http://localhost:8082");
  const realm = env.PERF_REALM ?? "nlportal";

  return {
    graphqlUrl: `${backend}/graphql`,
    tokenUrl: `${keycloak}/realms/${realm}/protocol/openid-connect/token`,
    clientId: env.PERF_CLIENT_ID ?? "nl-portal",
    username: verplicht(env, "PERF_USERNAME"),
    password: verplicht(env, "PERF_PASSWORD"),
  };
}

export interface UserPool {
  prefix: string;
  aantal: number;
  password: string;
}

/** De gebruikers uit `mise run perf:seed` (burger-001..): elke VU krijgt er een, zodat niet alle VU's één gebruiker delen. */
export function loadUserPool(env: Env): UserPool {
  const aantal = Number(env.PERF_USERS ?? "10");
  if (!Number.isInteger(aantal) || aantal < 1) {
    throw new Error(`PERF_USERS moet een geheel getal van minimaal 1 zijn, kreeg ${env.PERF_USERS}`);
  }
  return { prefix: env.PERF_USERNAME_PREFIX ?? "burger-", aantal, password: verplicht(env, "PERF_PASSWORD") };
}

/** Gebruiker voor VU `vu` (1-gebaseerd); bij meer VU's dan gebruikers wordt rondgewisseld. */
export function gebruikerVoorVu(pool: UserPool, vu: number): { username: string; password: string } {
  const nummer = ((vu - 1) % pool.aantal) + 1;
  return { username: `${pool.prefix}${String(nummer).padStart(3, "0")}`, password: pool.password };
}

/** Configuratie voor de journeys: endpoints en client zoals `loadConfig`, de gebruiker komt per VU uit de pool. */
export function loadJourneyConfig(env: Env): { config: PerfConfig; pool: UserPool } {
  const pool = loadUserPool(env);
  return { config: loadConfig({ ...env, PERF_USERNAME: "per-vu", PERF_PASSWORD: pool.password }), pool };
}
