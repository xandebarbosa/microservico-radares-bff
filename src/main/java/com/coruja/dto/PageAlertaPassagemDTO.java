package com.coruja.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PageAlertaPassagemDTO  extends PageImpl<AlertaPassagemDTO> {
    //Esta classe especial é necessária para que o WebClient consiga "traduzir" a resposta paginada do outro serviço.
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public PageAlertaPassagemDTO(@JsonProperty("content") List<AlertaPassagemDTO> content,
                                 @JsonProperty("number") int number,
                                 @JsonProperty("size") int size,
                                 @JsonProperty("totalElements") Long totalElements,
                                 // As propriedades abaixo são ignoradas, mas necessárias para o Jackson
                                 @JsonProperty("pageable") JsonNode pageable,
                                 @JsonProperty("last") boolean last,
                                 @JsonProperty("totalPages") int totalPages,
                                 @JsonProperty("sort") JsonNode sort,
                                 @JsonProperty("first") boolean first,
                                 @JsonProperty("numberOfElements") int numberOfElements) {

        super(content, PageRequest.of(number, Math.max(size, 1)), totalElements != null ? totalElements : 0L);
    }
}
