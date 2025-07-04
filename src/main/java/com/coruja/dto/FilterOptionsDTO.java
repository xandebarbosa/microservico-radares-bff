package com.coruja.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FilterOptionsDTO {
    private List<String> rodovias;
    private List<String> pracas;
    private List<String> kms;
    private List<String> sentidos;
}
