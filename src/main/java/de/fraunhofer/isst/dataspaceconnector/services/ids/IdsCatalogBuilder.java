package de.fraunhofer.isst.dataspaceconnector.services.ids;

import java.net.URI;

import de.fraunhofer.iais.eis.ResourceCatalog;
import de.fraunhofer.iais.eis.ResourceCatalogBuilder;
import de.fraunhofer.isst.dataspaceconnector.model.Catalog;
import de.fraunhofer.isst.dataspaceconnector.model.OfferedResource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Converts DSC Artifacts to Infomodel Artifacts.
 */
@Component
@RequiredArgsConstructor
public class IdsCatalogBuilder extends AbstractIdsBuilder<Catalog, ResourceCatalog> {

    private final @NonNull IdsResourceBuilder<OfferedResource> resourceBuilder;

    @Override
    protected ResourceCatalog createInternal(final Catalog catalog, final URI baseUri,
                                             final int currentDepth, final int maxDepth) {
        // Build children.
        final var resources = create(resourceBuilder,
                                     catalog.getOfferedResources(), baseUri, currentDepth,
                                     maxDepth);
        final var builder = new ResourceCatalogBuilder(getAbsoluteSelfLink(catalog, baseUri));
        resources.ifPresent(builder::_offeredResource_);

        return builder.build();
    }
}
