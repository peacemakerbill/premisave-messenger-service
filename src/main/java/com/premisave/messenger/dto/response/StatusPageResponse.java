package com.premisave.messenger.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatusPageResponse {

    private List<StatusResponse> items;
    private int page;
    private int size;
    private boolean hasMore;
}