package io.openshift.boosters;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.concurrent.TimeUnit;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;
import javax.json.JsonWriter;

import com.jayway.restassured.RestAssured;
import com.jayway.restassured.filter.log.LogDetail;
import com.jayway.restassured.http.ContentType;
import com.jayway.restassured.response.Response;
import io.openshift.booster.test.OpenShiftTestAssistant;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static com.jayway.awaitility.Awaitility.await;
import static com.jayway.restassured.RestAssured.delete;
import static com.jayway.restassured.RestAssured.get;
import static com.jayway.restassured.RestAssured.given;
import static com.jayway.restassured.RestAssured.put;
import static com.jayway.restassured.RestAssured.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * @author Heiko Braun
 */
public class OpenshiftIT {

    private static final OpenShiftTestAssistant openshift = new OpenShiftTestAssistant();

    @BeforeClass
    public static void setup() throws Exception {
        // Deploy the database and wait until it's ready.
        openshift.deploy("database", new File("src/test/resources/templates/database.yml"));
        openshift.awaitPodReadinessOrFail(
                pod -> "my-database".equals(pod.getMetadata().getLabels().get("app"))
        );

        System.out.println("Database ready");

        // the application itself
        openshift.deployApplication();

        // wait until the pods & routes become available
        openshift.awaitApplicationReadinessOrFail();

        await().atMost(5, TimeUnit.MINUTES).until(() -> {
            try {
                Response response = get();
                return response.getStatusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        });

        RestAssured.baseURI = RestAssured.baseURI + "/api/fruits";
    }

    @AfterClass
    public static void teardown() throws Exception {
        openshift.cleanup();
    }

    @Before
    public void removeAllData() {
        String jsonData = when()
                .get()
                .then()
                .extract().asString();

        JsonArray array = Json.createReader(new StringReader(jsonData)).readArray();
        array.forEach(val -> delete("/" + ((JsonObject) val).getInt("id")));
    }

    @Test
    public void testRetrieveNoFruit() {
        get()
                .then()
                .assertThat().statusCode(200)
                .body(is("[]"));
    }

    @Test
    public void testWithOneFruit() {
        createFruit("Peach");

        String payload = get()
                .then()
                .assertThat().statusCode(200)
                .extract().asString();

        JsonArray array = Json.createReader(new StringReader(payload)).readArray();

        assertThat(array).hasSize(1);
        assertThat(array.get(0).getValueType()).isEqualTo(JsonValue.ValueType.OBJECT);

        JsonObject obj = (JsonObject) array.get(0);
        assertThat(obj.getInt("id")).isNotNull().isGreaterThan(0);

        given()
                .pathParam("fruitId", obj.getInt("id"))
                .when()
                .get("/{fruitId}")
                .then()
                .assertThat().statusCode(200)
                .body(containsString("Peach"));
    }

    @Test
    public void testCreateFruit() {
        String payload = given()
                .contentType(ContentType.JSON)
                .body(convert(Json.createObjectBuilder().add("name", "Raspberry").build()))
                .post()
                .then()
                .assertThat().statusCode(201)
                .extract().asString();

        JsonObject obj = Json.createReader(new StringReader(payload)).readObject();
        assertThat(obj).isNotNull();
        assertThat(obj.getInt("id")).isNotNull().isGreaterThan(0);
        assertThat(obj.getString("name")).isNotNull().isEqualTo("Raspberry");
    }

    @Test
    public void testCreateInvalidPayload() {
        given()
                .contentType(ContentType.TEXT)
                .body("")
                .post()
                .then()
                .assertThat().statusCode(415)
                .extract().asString();
    }

    @Test
    public void testCreateIllegalPayload() {
        Fruit badFruit = new Fruit("Carrot");
        badFruit.setId(2);

        String payload = given()
                .contentType(ContentType.JSON)
                .body(badFruit)
                .post()
                .then()
                .assertThat().statusCode(422)
                .extract().asString();

        JsonObject obj = Json.createReader(new StringReader(payload)).readObject();
        assertThat(obj).isNotNull();
        assertThat(obj.getString("error")).isNotNull();
        assertThat(obj.getInt("code")).isNotNull().isEqualTo(422);
    }

//    @Test
//    public void testUpdate() {
//        Integer id = createFruit("Pear");
//
//        Fruit pear = given()
//                .pathParam("fruitId", id)
//                .when()
//                .get("/{fruitId")
//                .then()
//                .assertThat().statusCode(200)
//                .extract().as(Fruit.class);
//
//        pear.setName("Not Pear");
//
//        Fruit updatedPear = given()
//                .pathParam("fruitId", pear.getId())
//                .body(pear)
//                .when()
//                .put("/{fruitId}")
//                .then()
//                .assertThat().statusCode(200)
//                .extract().as(Fruit.class);
//
//        assertThat(pear.getId()).isEqualTo(updatedPear.getId());
//        assertThat(pear.getName()).isNotEqualTo(updatedPear.getName());
//        assertThat(updatedPear.getName()).isEqualTo("Not Pear");
//    }
//
//    @Test
//    public void testUpdateWithUnknownId() {
//
//    }
//
//    @Test
//    public void testUpdateWithInvalidPayload() {
//
//    }
//
//    @Test
//    public void testUpdateWithIllegalPayload() {
//
//    }

    @Test
    public void testDelete() {
        Integer id = createFruit("Orange");

        delete("/" + id)
                .then()
                .assertThat().statusCode(204);

        get()
                .then()
                .assertThat().statusCode(200)
                .body(is("[]"));
    }

    @Test
    public void testDeleteWithUnknownId() {
        delete("/unknown")
                .then()
                .assertThat().statusCode(404);

        get()
                .then()
                .assertThat().statusCode(200)
                .body(is("[]"));
    }

    private Integer createFruit(String name) {
        String payload = given()
                .contentType(ContentType.JSON)
                .body(convert(Json.createObjectBuilder().add("name", name).build()))
                .post()
                .then().log().ifValidationFails(LogDetail.ALL)
                .assertThat().statusCode(201)
                .extract().asString();

        JsonObject obj = Json.createReader(new StringReader(payload)).readObject();
        assertThat(obj).isNotNull();
        assertThat(obj.getInt("id")).isNotNull().isGreaterThan(0);

        return obj.getInt("id");
    }

    private String convert(JsonObject object) {
        StringWriter stWriter = new StringWriter();
        JsonWriter jsonWriter = Json.createWriter(stWriter);
        jsonWriter.writeObject(object);
        jsonWriter.close();

        return stWriter.toString();
    }
}

