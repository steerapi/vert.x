package vertx.tests.busmods.workqueue;

import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.EventBus;
import org.vertx.java.core.eventbus.JsonMessage;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.newtests.TestClientBase;


/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class TestClient extends TestClientBase {

  private EventBus eb = EventBus.instance;

  @Override
  public void start() {
    super.start();
    tu.appReady();
  }

  @Override
  public void stop() {
    super.stop();
  }

  int count;

  public void test1() throws Exception {

    final int numMessages = 30;

    eb.registerJsonHandler("done", new Handler<JsonMessage>() {
      public void handle(JsonMessage message) {
        if (++count == numMessages) {
          eb.unregisterJsonHandler("done", this);
          tu.testComplete();
        }
      }
    });

    for (int i = 0; i < numMessages; i++) {
      JsonObject obj = new JsonObject().putString("blah", "wibble" + i);
      eb.sendJson("orderQueue", obj);
    }
  }

}
