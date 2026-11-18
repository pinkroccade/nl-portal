/** De GraphQL-queries van de read-only journeys. De sleutel is de operatienaam, ook de `name`-tag in k6. */
export const journeyOperaties = {
  getZaken: `query getZaken { getZaken(page: 1, pageSize: 10) { content { uuid identificatie omschrijving startdatum einddatum status { statustype { omschrijving } } } totalElements } }`,
  getZaak: `query getZaak($id: UUID!) { getZaak(id: $id) { uuid identificatie omschrijving startdatum einddatum zaaktype { omschrijving } status { datumStatusGezet statustype { omschrijving isEindstatus } } statusGeschiedenis { datumStatusGezet statustype { omschrijving } } statussen { omschrijving isEindstatus } documenten { uuid titel bestandsnaam formaat creatiedatum } } }`,
  getTakenV2: `query getTakenV2 { getTakenV2(pageNumber: 1, pageSize: 10) { content { id titel status soort verloopdatum } totalElements } }`,
  getPersoonV2: `query getPersoonV2 { getPersoonV2 { burgerservicenummer leeftijd naam { voornamen geslachtsnaam volledigeNaam } geboorte { datum { datum } } } }`,
} as const;
