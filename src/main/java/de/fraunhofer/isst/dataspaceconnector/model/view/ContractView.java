package de.fraunhofer.isst.dataspaceconnector.model.view;

import lombok.Data;
import org.springframework.hateoas.RepresentationModel;

import java.util.Date;
import java.util.Map;

@Data
public class ContractView  extends RepresentationModel<ContractView> {
    private Date creationDate;
    private Date modificationDate;
    private String title;
    private Map<String, String> additional;
}
