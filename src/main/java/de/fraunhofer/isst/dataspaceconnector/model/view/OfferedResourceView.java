package de.fraunhofer.isst.dataspaceconnector.model.view;

import lombok.Data;
import org.springframework.hateoas.RepresentationModel;

import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
public class OfferedResourceView extends RepresentationModel<OfferedResourceView> {

    private Date creationDate;
    private Date modificationDate;

    /**
     * The title of the resource.
     */
    private String title;

    /**
     * The description of the resource.
     */
    private String description;

    /**
     * The keywords of the resource.
     */
    private List<String> keywords;

    /**
     * The publisher of the resource.
     */
    private URI publisher;

    /**
     * The language of the resource.
     */
    private String language;

    /**
     * The licence of the resource.
     */
    private URI licence;

    /**
     * The version of the resource.
     */
    private long version;


    private Map<String, String> additional;
}
