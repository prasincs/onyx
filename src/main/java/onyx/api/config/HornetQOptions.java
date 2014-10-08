package onyx.api.config;


public class HornetQOptions extends Options {

    private HornetQMode mode = HornetQMode.VM;
    private boolean server = false;
    private HornetQServerType serverType= HornetQServerType.DEFAULT;

    public enum HornetQMode {
        VM,
        UDP,
        JGROUPS
    }

    public enum HornetQServerType {
        VM, EMBEDDED, DEFAULT
    }

    protected HornetQOptions(){
        super();
    }

    public HornetQOptions withMode(HornetQMode mode){
        this.mode = mode;
        return this;
    }

    public HornetQOptions isServer(boolean val){
        this.server =   val;
        return this;
    }

    public HornetQOptions withServerType(HornetQServerType serverType){
        this.serverType = serverType;
        return this;
    }

}
