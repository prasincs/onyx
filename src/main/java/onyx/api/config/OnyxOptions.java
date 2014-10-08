package onyx.api.config;


public class OnyxOptions extends Options {

    private int revokeDelay;
    private CoordinatorOptions coordinatorOptions;

    protected OnyxOptions(){
        super();
    }

    public OnyxOptions withCorrdinatorOptions(CoordinatorOptions coordinatorOpts){
        this.coordinatorOptions = coordinatorOpts;
        return this;
    }
}
