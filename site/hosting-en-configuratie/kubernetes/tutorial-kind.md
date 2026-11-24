# Tutorial: een eerste NL Portal op kind

*Diátaxis: tutorial.* Je installeert NL Portal met de Helm-chart op een lokaal Kubernetes-cluster (kind) en ziet de pods ready worden. Reken op een kwartier.

Je doet het in deze volgorde: cluster maken, namespace met Pod Security "restricted", een Redis voor de sessies, de Secrets, de chart installeren en
controleren. Je gebruikt alleen minimale instellingen: alle modules staan uit, en de inlogkoppeling met Keycloak is een dummy. Je kunt dus nog niet
inloggen; dat komt in de [how-to's](./index.md) aan bod. Het doel is dat je ziet hoe de chart in elkaar zit.

Je hebt nodig: Docker, [kind](https://kind.sigs.k8s.io/), `kubectl`, `helm` (3.x) en Node.js (alleen om één sleutel te genereren).

## 1. Een cluster en een namespace

```shell
kind create cluster --name nl-portal-tutorial
kubectl create namespace nl-portal
kubectl label namespace nl-portal pod-security.kubernetes.io/enforce=restricted
```

Het label zorgt dat Kubernetes elke pod weigert die niet aan Pod Security "restricted" voldoet. De chart is daar zo op ingesteld
([securitymodel](./uitleg-securitymodel.md)), dus je pods draaien zonder uitzonderingen.

## 2. De Secrets

De chart maakt zelf geen Secrets: je wijst bestaande Secrets aan. Je maakt er twee: het backend-Secret met het Redis-wachtwoord, en een Secret met de
clientsleutel van de tenant (de backend logt met `private_key_jwt` in bij Keycloak, zie [Keycloak](../keycloak.md)). De backend eist een geldige RSA-sleutel van
minstens 3072 bit, dus genereer er een:

```shell
node -e "const {privateKey}=require('node:crypto').generateKeyPairSync('rsa',{modulusLength:3072});const j=privateKey.export({format:'jwk'});j.kid='tutorial';j.alg='PS256';j.use='sig';require('node:fs').writeFileSync('clientsleutel.jwk.json',JSON.stringify(j))"

kubectl -n nl-portal create secret generic nl-portal-backend --from-literal=REDIS_PASSWORD="$(openssl rand -hex 16)"
kubectl -n nl-portal create secret generic nl-portal-sleutel-00000099000000000001 --from-file=clientsleutel.jwk.json=clientsleutel.jwk.json
```

Onder Windows PowerShell maak je het wachtwoord met `-join ((48..57 + 97..102) | Get-Random -Count 32 | % {[char]$_})`. In een echt cluster vult
External Secrets Operator deze Secrets uit Vault: zie [Secrets uit Vault via ESO](./how-to-secrets-eso.md).

## 3. Redis

De backend bewaart de portaalsessies in Redis; zonder Redis kan niemand inloggen en start de backend niet goed op. Redis zit niet in de chart
([architectuur](./uitleg-architectuur.md)). Voor deze tutorial draai je een eenvoudige Redis zonder TLS en zonder persistentie, die ook aan "restricted" voldoet. Sla dit op als `redis.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis
spec:
  replicas: 1
  selector:
    matchLabels: { app: redis }
  template:
    metadata:
      labels: { app: redis }
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 999
        seccompProfile: { type: RuntimeDefault }
      containers:
        - name: redis
          image: redis:7-alpine
          args: ["--requirepass", "$(REDIS_PASSWORD)", "--save", "", "--appendonly", "no"]
          env:
            - name: REDIS_PASSWORD
              valueFrom: { secretKeyRef: { name: nl-portal-backend, key: REDIS_PASSWORD } }
          ports: [{ containerPort: 6379 }]
          securityContext:
            allowPrivilegeEscalation: false
            capabilities: { drop: [ALL] }
---
apiVersion: v1
kind: Service
metadata:
  name: redis
spec:
  selector: { app: redis }
  ports: [{ port: 6379 }]
```

```shell
kubectl -n nl-portal apply -f redis.yaml
kubectl -n nl-portal rollout status deployment/redis
```

## 4. De chart installeren

Sla dit op als `values.yaml`. Het is de kleinste set waarmee de chart rendert: de naam van het Secret, de Redis-host, de publieke URL van Keycloak (hier een
dummy: je logt in deze tutorial niet in) en één tenant. Een tenantbestand bevat alleen *namen* van geheimen, nooit waarden.

```yaml
backend:
  existingSecret: nl-portal-backend
  keycloakBrokerUrl: https://login.example.nl
  redis:
    host: redis
    ssl: false          # alleen in deze tutorial; in productie blijft TLS aan
tenancy:
  tenants:
    base.yaml: |
      omgevingstype: test
      inlogmiddelen: [test]
      minimumNiveau: basis
    "00000099000000000001.yaml": |
      oin: "00000099000000000001"
      naam: Gemeente De Bosrand
      domeinen:
        - localhost
      thema:
        titel: Mijn De Bosrand
      authenticatie:
        issuer: https://login.example.nl/realms/debosrand
        sleutelBestand: 00000099000000000001/clientsleutel.jwk.json
  keys:
    - oin: "00000099000000000001"
      existingSecret: nl-portal-sleutel-00000099000000000001
```

```shell
helm install nl-portal oci://ghcr.io/pinkroccade/charts/nl-portal --version 0.1.0 -n nl-portal -f values.yaml
```

Haal je een melding als `Geen tenantbestanden gevonden` of `het sleutelbestand is geen geldige JWK` uit de backend-logs? Dan klopt een tenantbestand of de
sleutel niet; de backend start bewust niet met een ongeldige tenantconfiguratie.

## 5. Controleren

```shell
kubectl -n nl-portal wait --for=condition=Ready pod --all --timeout=240s
kubectl -n nl-portal get pods,svc
```

Je ziet de pods `nl-portal-backend-…` en `nl-portal-frontend-…` en de Services `nl-portal-backend` en `nl-portal-frontend`. Kijk of de frontend antwoordt:

```shell
kubectl -n nl-portal port-forward svc/nl-portal-frontend 8081:80
# in een tweede terminal:
curl -i http://localhost:8081/api/public/frontend
```

De frontend-nginx stuurt `/api` door naar de backend, dus een antwoord (HTTP 200) bewijst dat frontend, backend en Redis samenwerken.

## Opruimen

```shell
kind delete cluster --name nl-portal-tutorial
```

## Hoe nu verder

- De chart in je eigen cluster krijgen: [De chart opnemen in een base-chart](./how-to-base-chart.md).
- Inloggen werkend krijgen: een Keycloak met een realm per tenant, zie [Infra-vereisten](./referentie-infra-vereisten.md).
- Begrijpen wat je net deed: [Architectuur](./uitleg-architectuur.md).
