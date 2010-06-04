package se.scalablesolutions.akka.camel;

import org.apache.camel.Body;
import org.apache.camel.Header;

import se.scalablesolutions.akka.actor.annotation.consume;

/**
 * @author Martin Krasser
 */
public class PojoBase {

    public String m1(String b, String h) {
        return "m1base: " + b + " " + h;
    }

    @consume("direct:m2base")
    public String m2(@Body String b, @Header("test") String h) {
        return "m2base: " + b + " " + h;
    }

    @consume("direct:m3base")
    public String m3(@Body String b, @Header("test") String h) {
        return "m3base: " + b + " " + h;
    }

    @consume("direct:m4base")
    public String m4(@Body String b, @Header("test") String h) {
        return "m4base: " + b + " " + h;
    }

    public void m5(@Body String b, @Header("test") String h) {
    }
}
