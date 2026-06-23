package com.onelake.common.modeling;

public interface DwdModelRunSynchronizer {

    boolean refreshByDagsterRunId(String dagsterRunId);
}
