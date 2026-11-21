# syntax=docker/dockerfile:1
# Eigen Keycloak-image van NL Portal: Keycloak met de NL Portal-provider (identity-provider-mappers, zie provider/).
# Bouwen (vanuit de repo-root): `mise run keycloak:image`; dat bouwt eerst de provider-jar en geeft de versie mee.
#
# Het basisimage staat op digest (een tag is verplaatsbaar). De versie in KEYCLOAK_VERSION moet gelijk zijn aan
# `keycloakVersion` in provider/gradle.properties, de versie waartegen de provider is gecompileerd: de RUN-stap
# hieronder controleert dat de Keycloak-jars in het basisimage die versie hebben, `mise run keycloak:check-version`
# controleert de bestanden zelf. Renovate en handmatig bijwerken: versie, digest en gradle.properties samen wijzigen.
ARG KEYCLOAK_VERSION=26.8.2
ARG KEYCLOAK_DIGEST=sha256:7e66a96e4739239070aeaf72e4c5decf0f7f682cfc725b9f7ea2b336eaba38b3

# --- builder: provider erin en `kc.sh build` (geoptimaliseerde start, geen start-dev) ---
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}@${KEYCLOAK_DIGEST} AS builder
ARG KEYCLOAK_VERSION
# Bouw-opties staan vast in de image en gelden bij `start --optimized`; ze moeten bij het draaien gelijk blijven.
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=false
# Faalt als het basisimage een andere Keycloak-versie heeft dan waartegen de provider is gebouwd.
RUN test -f "/opt/keycloak/lib/lib/main/org.keycloak.keycloak-services-${KEYCLOAK_VERSION}.jar" \
    || { echo "Keycloak ${KEYCLOAK_VERSION} verwacht, het basisimage heeft een andere versie" >&2; exit 1; }
COPY --chown=1000:0 provider/build/libs/nlportal-keycloak-provider.jar /opt/keycloak/providers/nlportal-keycloak-provider.jar
RUN /opt/keycloak/bin/kc.sh build

# --- image ---
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}@${KEYCLOAK_DIGEST}
ARG KEYCLOAK_VERSION
LABEL org.opencontainers.image.title="nl-portal-keycloak" \
      org.opencontainers.image.description="Keycloak met de NL Portal-provider voor sessienotities (acr en BSN) uit DigiD en wallet" \
      org.opencontainers.image.licenses="EUPL-1.2" \
      nl.nlportal.keycloak.version="${KEYCLOAK_VERSION}"
COPY --from=builder --chown=1000:0 /opt/keycloak/ /opt/keycloak/
# Niet-root (uid 1000) staat al in het basisimage.
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
