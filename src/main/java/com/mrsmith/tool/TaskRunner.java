package com.mrsmith.tool;

public interface TaskRunner {

    TaskResult run(String prompt, String agentName, String taskId);
}
