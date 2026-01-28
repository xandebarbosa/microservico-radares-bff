package com.coruja.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RadarPageDTOBuscaLocal  implements Serializable {
    private List<RadarDTO> content;
    private PageMetadataBuscaLocal page;
}
