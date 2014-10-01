package onyx.api;

import clojure.lang.RT;
import clojure.lang.Var;

/**
 * Submit Job interface
 */
public class Coordinator {

    public static Object registerPeer(Object peerNode){
       return REGISTER_PEER.invoke(peerNode);
    }

    public static Object submitJob(Object job){
        return SUBMIT_JOB.invoke(job);
    }

    public static Object awaitJobCompletion(Object jobId){
        return AWAIT_JOB_COMPLETION.invoke(jobId);
    }
    //private static final Var REQUIRE = RT.var("clojure.core", "require");
    private static final Var SUBMIT_JOB = RT.var("onyx.api.ISubmit", "submit-job");
    private static final Var REGISTER_PEER = RT.var("onyx.api.IRegister", "register-peer");
    private static final Var AWAIT_JOB_COMPLETION = RT.var("onyx.api.IAwait", "await-job-completion");
}
