package onyx.api.config;


public abstract class Options {
    String id = null;
    protected Options(){

    }

    public Options withID(String id){
        this.id = id;
        return this;
    }
}
