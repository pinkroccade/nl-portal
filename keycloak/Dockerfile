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
# Rechten vastleggen: het basisimage geeft groep 0 schrijfrechten overal (`chmod -R g+rwX`) en het proces (uid 1000) zit in
# groep 0. Hier gaat alles naar root met modus 755/644 (geen schrijfrechten voor groep of anderen); alleen data/ (met
# data/tmp, waar `start --optimized` naar schrijft) blijft van uid 1000. Het uitvoerbit van scripts blijft staan.
USER root
RUN chown -R 0:0 /opt/keycloak     && chmod -R u=rwX,go=rX /opt/keycloak     && chmod u=rwx,go=rx /opt/keycloak     && mkdir -p /opt/keycloak/data/tmp     && chown -R 1000:0 /opt/keycloak/data     && chmod -R u=rwX,g=rwX,o= /opt/keycloak/data

# --- image ---
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}@${KEYCLOAK_DIGEST}
ARG KEYCLOAK_VERSION
# sha256 van de provider-jar die in dit image zit (zet `mise run keycloak:image`), zodat het image herleidbaar is tot een build.
ARG PROVIDER_SHA256=onbekend
LABEL org.opencontainers.image.title="nl-portal-keycloak" \
      org.opencontainers.image.description="Keycloak met de NL Portal-provider voor sessienotities (acr en BSN) uit DigiD en wallet" \
      org.opencontainers.image.licenses="EUPL-1.2" \
      nl.nlportal.keycloak.version="${KEYCLOAK_VERSION}" \
      nl.nlportal.keycloak.provider.sha256="${PROVIDER_SHA256}"
# Code (bin, conf, lib, providers, themes) is van root en voor het proces (uid 1000, groep 0) alleen leesbaar: na
# code-executie kan het proces zijn eigen jars en providers niet overschrijven, dus een kwaadaardige provider overleeft een
# herstart niet. Alleen data/ (en /tmp) is schrijfbaar. De rechten worden in de builder-stage gezet en hier ongewijzigd
# overgenomen; `integratietest/image-rechten.test.mjs` bewijst het in het gebouwde image.
COPY --from=builder /opt/keycloak/ /opt/keycloak/
# De map /opt/keycloak zelf behoudt bij COPY de eigenaar van het basisimage (uid 1000); alleen die map (geen -R) naar root.
USER root
RUN chown 0:0 /opt/keycloak && chmod 755 /opt/keycloak
# Expliciet niet-root; het basisimage heeft dezelfde ENTRYPOINT, die hier dus niet herhaald wordt.
USER 1000
CMD ["start", "--optimized"]
