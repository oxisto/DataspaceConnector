package de.fraunhofer.isst.dataspaceconnector.controller.v2;

import de.fraunhofer.isst.dataspaceconnector.model.v2.Catalog;
import de.fraunhofer.isst.dataspaceconnector.model.v2.CatalogDesc;
import de.fraunhofer.isst.dataspaceconnector.services.resources.v2.CatalogResourceLinker;
import de.fraunhofer.isst.dataspaceconnector.services.resources.v2.CatalogService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/catalogs")
class CatalogController extends BaseResourceController<Catalog, CatalogDesc,
        CatalogService> {
}

@RestController
@RequestMapping("/catalogs/{id}/resources")
class CatalogResources extends BaseResourceChildController<CatalogResourceLinker> {
}
