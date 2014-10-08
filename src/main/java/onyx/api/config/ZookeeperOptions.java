package onyx.api.config;


public class ZookeeperOptions extends Options {

    private String address;
    private boolean server = false;
    private int port = 2181;

    protected ZookeeperOptions(){
        super();
    }

    public ZookeeperOptions withAddress(String connectString){
        this.address = connectString;
        return this;
    }

    public ZookeeperOptions isServer(boolean val){
        this.server = val;
        return this;
    }

    public ZookeeperOptions atPort(int port){
        this.port = port;
        return this;
    }
}
