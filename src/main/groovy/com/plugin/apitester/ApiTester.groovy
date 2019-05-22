package com.plugin.apitester;

import com.dtolabs.rundeck.core.common.INodeEntry;
import com.dtolabs.rundeck.core.execution.workflow.steps.FailureReason;
import com.dtolabs.rundeck.core.execution.workflow.steps.node.NodeStepException;
import com.dtolabs.rundeck.core.plugins.Plugin
import com.dtolabs.rundeck.plugins.ServiceNameConstants;
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty
import com.dtolabs.rundeck.plugins.descriptions.SelectValues
import com.dtolabs.rundeck.plugins.descriptions.TextArea;
import com.dtolabs.rundeck.plugins.step.PluginStepContext
import com.dtolabs.rundeck.plugins.PluginLogger;
import com.dtolabs.rundeck.plugins.step.NodeStepPlugin
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import net.thisptr.jackson.jq.JsonQuery
import net.thisptr.jackson.jq.Scope
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.apache.commons.lang.StringEscapeUtils
import org.codehaus.groovy.runtime.powerassert.PowerAssertionError

import java.util.concurrent.TimeUnit;


@Plugin(service=ServiceNameConstants.WorkflowNodeStep,name="api-tester")
@PluginDescription(title="api-tester", description="Test HTTP api endpoints")
public class ApiTester implements NodeStepPlugin {

    public static final String SERVICE_PROVIDER_NAME = "api-tester";

    @PluginProperty(title="Endpoint",description = "The http(s) endpoint to hit")
    String endpoint

    @PluginProperty(title="Http Method",description = "The method to use for the request", defaultValue = "GET")
    @SelectValues(values=["GET","POST","PUT","PATCH","HEAD","OPTIONS","DELETE"])
    String method

    @PluginProperty(title="Request Payload",description = "Request Payload")
    @TextArea
    String requestPayload

    @PluginProperty(title="Request Payload Content Type",description = "If sending a request payload, this specifies the payload mime type.")
    String requestContentType

    @PluginProperty(title="Request Headers",description = "Request headers")
    @TextArea
    String requestHeaders

    @PluginProperty(title="Bypass Response Content Check", description = "Allows bypassing the response content check", defaultValue = "false")
    boolean bypassResponseContent = false

    @PluginProperty(title="Expected Response",description = "The expected result from the endpoint")
    @TextArea
    String expectedResponse

    @PluginProperty(title="JQ json verification expression",description = "Verify the response with a JQ expression, if the response is json.")
    String jqExpression

    @PluginProperty(title="Expected Headers",description = "Expected response headers")
    @TextArea
    String expectedHeaders

    @PluginProperty(title="Expected Status Code",description = "Expected status code",defaultValue = "200")
    long expectedCode

    @PluginProperty(title="Timeout",description = "Timeout in seconds to wait for endpoint to respond",defaultValue = "30")
    long timeout

    @PluginProperty(title="Log Response", description = "Logs the response", defaultValue = "false")
    boolean logResponse = false

    static final ObjectMapper mapper = new ObjectMapper()
   /**
     * This enum lists the known reasons this plugin might fail
     */
   static enum Reason implements FailureReason{
        Exception,
        TestFailed
   }

   /**
       * The {@link #performNodeStep(com.dtolabs.rundeck.plugins.step.PluginStepContext,
       * com.dtolabs.rundeck.core.common.INodeEntry)} method is invoked when your plugin should perform its logic for the
       * appropriate node.  The {@link PluginStepContext} provides access to the configuration of the plugin, and details
       * about the step number and context.
       * <p/>
       * The {@link INodeEntry} parameter is the node that should be executed on.  Your plugin should make use of the
       * node's attributes (such has "hostname" or any others required by your plugin) to perform the appropriate action.
       */
      @Override
      public void executeNodeStep(final PluginStepContext context,
                                  final Map<String, Object> configuration,
                                  final INodeEntry entry) throws NodeStepException {
           PluginLogger logger= context.getLogger();

          OkHttpClient client = new OkHttpClient.Builder()
                  .connectTimeout(timeout, TimeUnit.SECONDS)
                  .readTimeout(timeout,TimeUnit.SECONDS)
                  .build()

          boolean failed = false
          Response okRsp = null
          StringWriter out = new StringWriter()
          try {
              HttpMethod method = HttpMethod.valueOf(method.toUpperCase())
              RequestBody rqBody = null;
              if (method.allowBody && requestPayload != null) {
                  MediaType ctype = MediaType.parse(requestContentType)
                  rqBody = RequestBody.create(ctype, requestPayload.bytes)
              }

              def rqBuilder = new Request.Builder().url(endpoint)

              def rqHdrs = convertHeaderString(requestHeaders)
              rqHdrs.each { header, value ->
                  rqBuilder.header(header, value)
                  logger.log(3,"Adding header: ${header}:${value}")
              }

              rqBuilder.method(method.name(), rqBody)
              okRsp = client.newCall(rqBuilder.build()).execute()

              try {
                  assert okRsp.code() == expectedCode
              } catch(PowerAssertionError err) {
                  out.append(err.toString())
                  failed = true
              }

              def eHdrs = convertHeaderString(expectedHeaders)
              eHdrs.each { k, v ->
                  String hdrVal = okRsp.header(k)
                  try {
                      assert v == hdrVal
                  } catch(PowerAssertionError err) {
                      out.append(err.toString())
                      failed = true
                  }
              }

              String response = okRsp.body().string()
              if(!bypassResponseContent) {
                  if(expectedResponse) {
                      try {
                          assert expectedResponse == response
                      } catch (PowerAssertionError err) {
                          out.append(err.toString())
                          int elen = expectedResponse?.length() ?: 0
                          int rlen = response.length()
                          if (rlen != elen)
                              out.append("Expected response to be: ${elen} characters. Was: ${rlen}\n")
                          if (rlen > elen) {
                              def range = elen..(rlen - 1)
                              out.append("Extra characters: ${StringEscapeUtils.escapeJava(response[range])}\n")
                          }
                          if (expectedResponse) appendFirstStringDifference(out, expectedResponse, response)
                          failed = true
                      }
                  }
                  if(jqExpression) {
                      JsonQuery q = JsonQuery.compile(jqExpression)

                      Scope scope = Scope.newEmptyScope()
                      scope.loadFunctions(Scope.class.classLoader)
                      List<JsonNode> result = q.apply(scope, mapper.readTree(response))

                      if(result.isEmpty() || !result[0].booleanValue()) {
                        out.append("JQ expression failed. Expression: ${jqExpression}")
                          failed = true
                      }
                  }
              }
              if(logResponse) {
                  logger.log(2,response)
              }
          } catch(Exception ex) {
              throw new NodeStepException("Exception when running test",ex,Reason.Exception,entry.getNodename())
          } finally {
              if(okRsp) okRsp.close()
          }

          if(failed) {
            throw new NodeStepException(out.toString(), Reason.TestFailed, entry.getNodename());
          }

      }

    Map<String,String> convertHeaderString(String headerString) {
        Map<String,String> hdrs = [:]
        if(!headerString || headerString.isEmpty()) return hdrs
        headerString.eachLine { header ->
            if(header.contains(":")) {
                def parts = header.split(":")
                hdrs[parts[0].trim()] = parts[1].trim()
            }
        }
        return hdrs
    }

    void appendFirstStringDifference(StringWriter out, String expected, String actual) {
        for(int i = 0; i < expected.length(); i++) {
            if(expected[i] != actual[i]) {
                out.append("Expected char at ${i} to be: ${expected[i]} was: ${actual[i]}\n")
                return
            }
        }
    }
}