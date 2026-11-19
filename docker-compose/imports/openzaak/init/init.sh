#!/bin/bash
set -e
echo ">>>>  NL Portal init script: Open Zaak <<<<"
until python /app/src/manage.py migrate --check >/dev/null 2>&1; do
    echo "Waiting for openzaak migrations..."
    sleep 5
done
# De inhoud van het demodocument (documenten.json) naar de bestandsopslag van Open Zaak.
cp -rn /app/fixture-media/. /app/private-media/
python /app/src/manage.py loaddata /app/generated/configuration.json catalogi documenten zaken || { echo ">>>> FOUT: loaddata mislukt <<<<"; exit 1; }
echo ">>>> Done <<<<"
