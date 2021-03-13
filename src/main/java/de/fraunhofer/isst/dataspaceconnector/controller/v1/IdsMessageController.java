package de.fraunhofer.isst.dataspaceconnector.controller.v1;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.fraunhofer.iais.eis.Artifact;
import de.fraunhofer.iais.eis.ArtifactResponseMessage;
import de.fraunhofer.iais.eis.BaseConnector;
import de.fraunhofer.iais.eis.Connector;
import de.fraunhofer.iais.eis.ContractAgreement;
import de.fraunhofer.iais.eis.ContractRejectionMessage;
import de.fraunhofer.iais.eis.DescriptionResponseMessage;
import de.fraunhofer.iais.eis.RejectionMessage;
import de.fraunhofer.iais.eis.Resource;
import de.fraunhofer.iais.eis.ResourceImpl;
import de.fraunhofer.iais.eis.ResponseMessage;
import de.fraunhofer.isst.dataspaceconnector.exceptions.ContractException;
import de.fraunhofer.isst.dataspaceconnector.exceptions.InvalidResourceException;
import de.fraunhofer.isst.dataspaceconnector.exceptions.MessageException;
import de.fraunhofer.isst.dataspaceconnector.exceptions.MessageResponseException;
import de.fraunhofer.isst.dataspaceconnector.exceptions.ResourceException;
import de.fraunhofer.isst.dataspaceconnector.exceptions.handled.ResponseMessageBuilderException;
import de.fraunhofer.isst.dataspaceconnector.model.QueryInput;
import de.fraunhofer.isst.dataspaceconnector.model.RequestedResource;
import de.fraunhofer.isst.dataspaceconnector.model.RequestedResourceDesc;
import de.fraunhofer.isst.dataspaceconnector.model.v1.BackendSource;
import de.fraunhofer.isst.dataspaceconnector.model.v1.ResourceMetadata;
import de.fraunhofer.isst.dataspaceconnector.model.v1.ResourceRepresentation;
import de.fraunhofer.isst.dataspaceconnector.services.messages.implementation.RequestMessageService;
import de.fraunhofer.isst.dataspaceconnector.services.resources.v2.backend.ArtifactService;
import de.fraunhofer.isst.dataspaceconnector.services.resources.v2.backend.RepresentationArtifactLinker;
import de.fraunhofer.isst.dataspaceconnector.services.resources.v2.backend.ResourceRepresentationLinker;
import de.fraunhofer.isst.dataspaceconnector.services.resources.v2.backend.ResourceService;
import de.fraunhofer.isst.dataspaceconnector.services.resources.v2.backendtofrontend.TemplateBuilder42;
import de.fraunhofer.isst.dataspaceconnector.services.usagecontrol.NegotiationService;
import de.fraunhofer.isst.dataspaceconnector.utils.EntityApiBridge;
import de.fraunhofer.isst.dataspaceconnector.utils.UUIDUtils;
import de.fraunhofer.isst.ids.framework.configuration.SerializerProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * This class provides endpoints for the communication with an IDS connector instance.
 */
@RestController
@RequestMapping("/api/ids")
@Tag(name = "IDS Messages", description = "Endpoints for invoke sending IDS messages")
@RequiredArgsConstructor
public class IdsMessageController {
    /**
     * The logging service.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(IdsMessageController.class);

    /**
     * The service for request messages.
     */
    private final @NonNull RequestMessageService requestMessageService;

    /**
     * The service for negotiations.
     */
    private final @NonNull NegotiationService negotiationService;

    /**
     * Requests metadata from an external connector by building an ArtifactRequestMessage.
     *
     * @param recipient  The target connector url.
     * @param elementId The requested element id.
     * @return OK or error response.
     */
    @PostMapping("/request/description")
    @Operation(summary = "Send ids description request message")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Ok"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "500", description = "Internal server error")})
    @PreAuthorize("hasPermission(#url, 'rw')")
    @ResponseBody
    public ResponseEntity<String> sendDescriptionRequestMessage(
            @Parameter(description = "The recipient URL.", required = true)
            @RequestParam("recipient") final URI recipient,
            @Parameter(description = "The URI of the requested resource.")
            @RequestParam(value = "elementId", required = false) final URI elementId) {
        Map<String, String> response;
        try {
            // Send DescriptionRequestMessage.
            response = requestMessageService.sendDescriptionRequest(recipient, elementId, "");
        } catch (ResponseMessageBuilderException exception) {
            // Failed to send the description request message.
            LOGGER.info(
                    "Failed to send or build a request. [exception=({})]", exception.getMessage());
            return new ResponseEntity<>("Failed to send description request message.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (MessageResponseException exception) {
            // Failed to read the description response message.
            LOGGER.info("Received invalid ids response. [exception=({})]", exception.getMessage());
            return new ResponseEntity<>(
                    "Failed to read the ids response message.", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        String header, payload;
        try {
            header = response.get("header");
            payload = response.get("payload");
        } catch (Exception exception) {
            // Failed to read the message parts.
            LOGGER.info("Received invalid ids response. [exception=({})]", exception.getMessage());
            return new ResponseEntity<>(
                    "Failed to read the ids response message.", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // Get ids response header.
        final var idsHeader = requestMessageService.getIdsHeader(header);
        if (!(idsHeader instanceof DescriptionResponseMessage)) {
            return returnRejectionMessage(idsHeader, response);
        }

        if (elementId != null) {
            // Save metadata to database.
            try {
                final var validationKey = saveMetadata(payload, elementId);
                return new ResponseEntity<>(
                        "Validation: " + validationKey + "\nResponse: " + payload, HttpStatus.OK);
            } catch (InvalidResourceException exception) {
                LOGGER.info("Could not save metadata to database. [exception=({})]",
                        exception.getMessage());
                return new ResponseEntity<>(
                        exception.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
            }
        } else {
            // Return self-description.
            return new ResponseEntity<>(payload, HttpStatus.OK);
        }
    }

    /**
     * Sends a contract request to a connector by building an ContractRequestMessage.
     *
     * @param recipient     The recipient url.
     * @param artifactId    The requested artifact id.
     * @param contractOffer The contract offer for the requested artifact.
     * @return OK or error response.
     */
    @RequestMapping("/request/contract")
    @Operation(summary = "Send ids contract request message")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Ok"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "500", description = "Internal server error")})
    @PreAuthorize("hasPermission(#url, 'rw')")
    @ResponseBody
    public ResponseEntity<String> sendContractRequestMessage(
            @Parameter(description = "The recipient URL.", required = true)
            @RequestParam("recipient") final URI recipient,
            @Parameter(description = "The URI of the requested artifact.", required = true)
            @RequestParam(value = "artifactId") final URI artifactId,
            @Parameter(description = "The contract offer for the requested resource.", required = true)
            @RequestBody final String contractOffer) {

        Map<String, String> response;
        try {
            // Start policy negotiation.
            final var request = negotiationService.buildContractRequest(contractOffer, artifactId);
            // Send ContractRequestMessage.
            response = requestMessageService.sendContractRequest(recipient, request.getId(), request.toRdf());
        } catch (IllegalArgumentException exception) {
            LOGGER.warn(
                    "Failed to build contract request. [exception=({})]", exception.getMessage());
            return new ResponseEntity<>(
                    "Failed to build contract request.", HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (ResponseMessageBuilderException exception) {
            // Failed to send the contract request message.
            LOGGER.info(
                    "Failed to send or build a request. [exception=({})]", exception.getMessage());
            return new ResponseEntity<>(
                    "Failed to send contract request message.", HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (MessageResponseException exception) {
            // Failed to read the contract response message.
            LOGGER.info("Received invalid ids response. [exception=({})]", exception.getMessage());
            return new ResponseEntity<>(
                    "Failed to read the ids response message.", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        String header, payload;
        try {
            header = response.get("header");
            payload = response.get("payload");
        } catch (Exception exception) {
            // Failed to read the message parts.
            LOGGER.info("Received invalid ids response. [exception=({})]", exception.getMessage());
            return new ResponseEntity<>(
                    "Failed to read the ids response message.", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // Get ids response header.
        final var idsHeader = requestMessageService.getIdsHeader(header);
        // TODO Add further responses (on contract offer or contract response).
        if (!(idsHeader instanceof ContractAgreement)) {
            return returnRejectionMessage(idsHeader, response);
        }

        // Get contract id.
        URI agreementId;
        try {
            agreementId = negotiationService.contractAccepted(recipient, header, payload);
        } catch (ContractException exception) {
            // Failed to read the contract.
            LOGGER.info("Could not read contract. [exception=({})]", exception.getMessage());
            return new ResponseEntity<>(
                    "Failed to read the received contract.", HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (MessageException exception) {
            // Failed to send contract agreement confirmation.
            LOGGER.info(
                    "Failed to send contract agreement. [exception=({})]", exception.getMessage());
            return new ResponseEntity<>("Negotiation sequence was not fully completed.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        if (agreementId == null) {
            // Failed to read the contract agreement.
            LOGGER.info("Received invalid contract agreement.");
            return new ResponseEntity<>(
                    "Received invalid contract agreement.", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return new ResponseEntity<>(String.valueOf(agreementId), HttpStatus.OK);
    }

    /**
     * Requests data from an external connector by building an ArtifactRequestMessage.
     *
     * @param recipient  The recipient url.
     * @param artifactId The requested artifact id.
     * @param contractId The id of the contract agreement.
     * @param resourceId The id of the resource to which the artifact is to be saved.
     * @param queryInput The query input to forward to the provider's backend API.
     * @return OK or error response.
     */
    @PostMapping("/request/artifact")
    @Operation(summary = "Send ids artifact request message")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Ok"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden"),
            @ApiResponse(responseCode = "500", description = "Internal server error")})
    @PreAuthorize("hasPermission(#url, 'rw')")
    @ResponseBody
    public ResponseEntity<String> sendArtifactRequestMessage(
            @Parameter(description = "The recipient URL.", required = true)
            @RequestParam("recipient") final URI recipient,
            @Parameter(description = "The requested artifactId.", required = true)
            @RequestParam(value = "artifactId") final URI artifactId,
            @Parameter(description = "The contract agreement id.")
            @RequestParam(value = "transferContract", required = false) final URI contractId,
            @Parameter(description = "The consumer's resource id.", required = true)
            @RequestParam("resourceId") final UUID resourceId,
            @Parameter(description = "The query parameters, path variables and headers to use when "
                    + "fetching the data from the backend system.")
            @RequestBody(required = false) final QueryInput queryInput) {

        if (!resourceExists(resourceId)) {
            // The resource does not exist.
            LOGGER.warn(String.format("Failed data request due to invalid key.\nRecipient: "
                            + "%s\nrequestedArtifact:%s\nkey:%s",
                    recipient.toString(), artifactId.toString(), resourceId.toString()));
            return new ResponseEntity<>(
                    "Your key is not valid. Please request metadata first.", HttpStatus.FORBIDDEN);
        }

        try {
            validateQueryInput(queryInput);
        } catch (IllegalArgumentException exception) {
            // There is an empty key or value string in the params or headers map
            LOGGER.debug("Invalid input for headers or params. [exception=({})]", exception.getMessage());
            return new ResponseEntity<>("Invalid input for headers or params. ",
                    HttpStatus.BAD_REQUEST);
        }

        Map<String, String> response;
        try {
            // Send ArtifactRequestMessage.
            response = requestMessageService.sendArtifactRequest(recipient, artifactId, contractId, new ObjectMapper().writeValueAsString(queryInput));
        } catch (ResponseMessageBuilderException exception) {
            // Failed to send the artifact request message.
            LOGGER.info(
                    "Failed to send or build a request. [exception=({})]", exception.getMessage());
            return new ResponseEntity<>(
                    "Failed to send artifact request message.", HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (MessageResponseException exception) {
            // Failed to read the artifact response message.
            LOGGER.info("Received invalid ids response. [exception=({})]", exception.getMessage());
            return new ResponseEntity<>(
                    "Failed to read the ids response message.", HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (JsonProcessingException exception) {
            // Could not parse query input (params and headers).
            LOGGER.debug("Could not parse query input from request body. [exception=({})]", exception.getMessage());
            return new ResponseEntity<>("Could not parse query input from request body.",
                    HttpStatus.BAD_REQUEST);
        }

        String header, payload;
        try {
            header = response.get("header");
            payload = response.get("payload");
        } catch (Exception exception) {
            // Failed to read the message parts.
            LOGGER.info("Received invalid ids response. [exception=({})]", exception.getMessage());
            return new ResponseEntity<>(
                    "Failed to read the ids response message.", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // Get ids response header.
        final var idsHeader = requestMessageService.getIdsHeader(header);
        if (!(idsHeader instanceof ArtifactResponseMessage)) {
            return returnRejectionMessage(idsHeader, response);
        }

        try {
            // Save data to database.
            saveData(payload, resourceId);
            return new ResponseEntity<>(String.format("Saved at: %s\nResponse: "
                            + "%s",
                    resourceId, payload),
                    HttpStatus.OK);
        } catch (ResourceException exception) {
            LOGGER.warn(
                    "Could not save data to database. [exception=({})]", exception.getMessage());
            return new ResponseEntity<>(
                    "Failed to save to database.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Checks for rejection or contract rejection message.
     *
     * @param responseMessage The ids response header.
     * @param response     The response content.
     * @return The response message
     */
    private ResponseEntity<String> returnRejectionMessage(
            final ResponseMessage responseMessage, final Map<String, String> response) {
        if (responseMessage instanceof ContractRejectionMessage) {
            return new ResponseEntity<>("Received contract rejection message: "
                    + response.get("payload"), HttpStatus.OK);
        } else if (responseMessage instanceof RejectionMessage) {
            return new ResponseEntity<>("Received rejection message: "
                    + response.get("payload"), HttpStatus.OK);
        } else {
            return new ResponseEntity<>("Received unexpected response: \n" + response,
                    HttpStatus.OK);
        }
    }

    /**
     * Checks a given query input. If any of the keys or values in the headers or params maps are
     * null, blank, or empty, an  exception is thrown.
     *
     * @param queryInput the query input to validate.
     * @throws IllegalArgumentException if any of the keys or values are null, blank, or empty.
     */
    private void validateQueryInput(final QueryInput queryInput) {
        if (queryInput != null && queryInput.getHeaders() != null) {
            for (Map.Entry<String, String> entry: queryInput.getHeaders().entrySet()) {
                if (entry.getKey() == null || entry.getKey().trim().isEmpty()
                        || entry.getValue() == null || entry.getValue().trim().isEmpty()) {
                    throw new IllegalArgumentException("Header key or value should not be null, blank or empty " +
                            "(key:" + entry.getKey() + ", value: " + entry.getValue() + ").");
                }
            }
        }
        if (queryInput != null && queryInput.getParams() != null) {
            for (Map.Entry<String, String> entry: queryInput.getParams().entrySet()) {
                if (entry.getKey() == null || entry.getKey().trim().isEmpty()
                        || entry.getValue() == null || entry.getValue().trim().isEmpty()) {
                    throw new IllegalArgumentException("Param key or value should not be null, blank or empty.");
                }
            }
        }
    }

    /******************************************
     * TODO: Some mess below
     *****************************************/

    @Autowired
    private ResourceService<RequestedResource, ?> requestedResourceService;

    @Autowired
    private ResourceRepresentationLinker<RequestedResource> requestedResourceRepresentationLinker;

    @Autowired
    private RepresentationArtifactLinker representationArtifactLinker;

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private SerializerProvider serializerProvider;

    @Autowired
    private TemplateBuilder42<RequestedResource, RequestedResourceDesc> templateBuilder;

    /**
     * Checks if a resource exists.
     *
     * @param resourceId The resource uuid.
     * @return true if the resource exists.
     */
    private boolean resourceExists(final UUID resourceId) {
        return requestedResourceService.doesExist(resourceId);
    }

    /**
     * Saves the data string to the internal database.
     *
     * @param response   The data resource as string.
     * @param resourceId The resource uuid.
     * @throws ResourceException if any.
     */
    private void saveData(final String response, final UUID resourceId) throws ResourceException {
        // final var representations = requestedResourceRepresentationLinker.get(resourceId);
         // final var artifacts =
               // representationArtifactLinker.get((UUID) representations.toArray()[0]);
        // TODO: Implement
        // artifactService.saveData((UUID) artifacts.toArray()[0], response);
    }

    /**
     * Saves the metadata to the internal database.
     *
     * @param response   The data resource as string.
     * @param resourceId The id of the resource
     * @return The UUID of the created resource.
     * @throws ResourceException        if any.
     * @throws InvalidResourceException If the ids object could not be deserialized.
     */
    public RequestedResource saveMetadata(final String response, final URI resourceId)
            throws ResourceException, InvalidResourceException {
        Resource resource;
        try {
            resource = serializerProvider.getSerializer().deserialize(response, ResourceImpl.class);
        } catch (Exception e) {
            resource = findResource(response, resourceId);
        }

        ResourceMetadata metadata;
        try {
            metadata = deserializeMetadata(resource);
        } catch (Exception exception) {
            LOGGER.info("Failed to deserialize metadata. [exception=({})]", exception.getMessage());
            throw new InvalidResourceException("Metadata could not be deserialized.");
        }

        try {
            return templateBuilder.build(EntityApiBridge.toRequestedResourceTemplate(null,
                    metadata));
        } catch (Exception exception) {
            LOGGER.info("Failed to save metadata. [exception=({})]", exception.getMessage());
            throw new ResourceException("Metadata could not be saved to database.");
        }
    }

    /**
     * Find a resource from a connector's resource catalog.
     *
     * @param payload    The message payload
     * @param resourceId The id of the resource
     * @return The resource object.
     * @throws InvalidResourceException If the payload could not be deserialized to a base
     *                                  connector.
     */
    private Resource findResource(final String payload, final URI resourceId) throws InvalidResourceException {
        Resource resource = null;
        try {
            Connector connector =
                    serializerProvider.getSerializer().deserialize(payload, BaseConnector.class);
            if (connector.getResourceCatalog() != null
                    && !connector.getResourceCatalog().isEmpty()) {
                for (Resource r : connector.getResourceCatalog().get(0).getOfferedResource()) {
                    if (r.getId().equals(resourceId)) {
                        resource = r;
                        break;
                    }
                }
            }
        } catch (Exception exception) {
            LOGGER.info("Failed to save metadata. [exception=({})]", exception.getMessage());
            throw new InvalidResourceException("Response could not be deserialized: " + payload);
        }
        return resource;
    }

    /**
     * Maps a received Infomodel resource to the internal metadata model.
     *
     * @param resource The resource
     * @return the metadata object.
     */
    private ResourceMetadata deserializeMetadata(final Resource resource) {
        var metadata = new ResourceMetadata();

        if (resource.getKeyword() != null) {
            List<String> keywords = new ArrayList<>();
            for (var t : resource.getKeyword()) {
                keywords.add(t.getValue());
            }
            metadata.setKeywords(keywords);
        }

        if (resource.getRepresentation() != null) {
            var representations = new HashMap<UUID, ResourceRepresentation>();
            for (final var r : resource.getRepresentation()) {
                int byteSize = 0;
                String name = null;
                String type = null;
                if (r.getInstance() != null && !r.getInstance().isEmpty()) {
                    final var artifact = (Artifact) r.getInstance().get(0);
                    if (artifact.getByteSize() != null) {
                        byteSize = artifact.getByteSize().intValue();
                    }
                    if (artifact.getFileName() != null) {
                        name = artifact.getFileName();
                    }
                    if (r.getMediaType() != null) {
                        type = r.getMediaType().getFilenameExtension();
                    }
                }

                ResourceRepresentation representation = new ResourceRepresentation(
                        UUIDUtils.uuidFromUri(r.getId()), type, byteSize, name,
                        new BackendSource(BackendSource.Type.LOCAL, null, null, null));

                representations.put(representation.getUuid(), representation);
            }
            metadata.setRepresentations(representations);
        }

        if (resource.getTitle() != null && !resource.getTitle().isEmpty()) {
            metadata.setTitle(resource.getTitle().get(0).getValue());
        }

        if (resource.getDescription() != null && !resource.getDescription().isEmpty()) {
            metadata.setDescription(resource.getDescription().get(0).getValue());
        }

        if (resource.getContractOffer() != null && !resource.getContractOffer().isEmpty()) {
            metadata.setPolicy(resource.getContractOffer().get(0).toRdf());
        }

        if (resource.getPublisher() != null) {
            metadata.setOwner(resource.getPublisher());
        }

        if (resource.getStandardLicense() != null) {
            metadata.setLicense(resource.getStandardLicense());
        }

        if (resource.getVersion() != null) {
            metadata.setVersion(resource.getVersion());
        }

        return metadata;
    }
}
