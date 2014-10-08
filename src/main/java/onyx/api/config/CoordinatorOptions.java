package onyx.api.config;

/**
 * Created by gautamp on 10/4/14.
 */
public class CoordinatorOptions extends  Options{
    private int revokeDelay = 1000;

    CoordinatorOptions(){
        super();
    }

    public CoordinatorOptions setRevokeDelay(int timeMs){
        this.revokeDelay = timeMs;
        return this;
    }
}
