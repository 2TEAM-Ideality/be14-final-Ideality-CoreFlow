package com.ideality.coreflow.project.query.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkDueTodayDTO {
    private Long workId;
    private String name;
    private Long parentTaskId;
    private String type; // TASK or SUBTASK
}
