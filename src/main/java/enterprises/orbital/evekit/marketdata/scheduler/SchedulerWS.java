package enterprises.orbital.evekit.marketdata.scheduler;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import enterprises.orbital.base.OrbitalProperties;
import enterprises.orbital.base.PersistentProperty;
import enterprises.orbital.db.ConnectionFactory.RunInVoidTransaction;
import enterprises.orbital.evekit.marketdata.EveKitMarketDataProvider;
import enterprises.orbital.evekit.marketdata.Instrument;
import enterprises.orbital.evekit.marketdata.Order;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * API for marketdata update scheduler.
 */
@Path("/ws/v1/scheduler")
@Consumes({
    "application/json"
})
@Produces({
    "application/json"
})
@Api(
    tags = {
        "Scheduler"
    },
    produces = "application/json",
    consumes = "application/json")
public class SchedulerWS {
  private static final Logger log                     = Logger.getLogger(SchedulerWS.class.getName());
  public static final String  PROP_MIN_SCHED_INTERVAL = "enterprises.orbital.evekit.marketdata.scheduler.minSchedInterval";
  public static final long    DEF_MIN_SCHED_INTERVAL  = TimeUnit.MILLISECONDS.convert(5, TimeUnit.MINUTES);

  @Path("/takenext")
  @GET
  @ApiOperation(
      value = "Get next instrument for which marketdata should be updated.  Caller is assigned the instrument and is responsible for releasing it when the update is complete.")
  @ApiResponses(
      value = {
          @ApiResponse(
              code = 200,
              message = "Next instrument to be updated",
              response = Instrument.class),
          @ApiResponse(
              code = 404,
              message = "No instrument ready to be scheduled , try again later."),
          @ApiResponse(
              code = 500,
              message = "Internal error"),
      })
  public Response takeNext(
                           @Context HttpServletRequest request) {
    long interval = PersistentProperty.getLongPropertyWithFallback(PROP_MIN_SCHED_INTERVAL, DEF_MIN_SCHED_INTERVAL);
    Instrument next;
    try {
      next = Instrument.takeNextScheduled(interval);
    } catch (Exception e) {
      log.log(Level.SEVERE, "DB error retrieving instrument, failing", e);
      return Response.status(Status.INTERNAL_SERVER_ERROR).build();
    }
    if (next == null) return Response.status(Status.NOT_FOUND).build();
    return Response.ok().entity(next).build();
  }

  @Path("/storeBatch")
  @POST
  @ApiOperation(
      value = "Populate a set of orders for an instrument.  Clean any orders that aren't listed.")
  @ApiResponses(
      value = {
          @ApiResponse(
              code = 200,
              message = "Population successful"),
          @ApiResponse(
              code = 500,
              message = "Internal error"),
      })
  public Response storeBatch(
                             @Context HttpServletRequest request,
                             @QueryParam("regionid") @ApiParam(
                                 name = "regionid",
                                 required = true,
                                 value = "Region where order is located") final int regionID,
                             @QueryParam("typeid") @ApiParam(
                                 name = "typeid",
                                 required = true,
                                 value = "Type ID of order") final int typeID,
                             @ApiParam(
                                 name = "orders",
                                 required = true,
                                 value = "Orders to popualte") final List<Order> orders) {
    final long at = OrbitalProperties.getCurrentTime();
    log.info("Processing " + orders.size() + " orders on (" + regionID + ", " + typeID + ")");
    try {
      EveKitMarketDataProvider.getFactory().runTransaction(new RunInVoidTransaction() {
        @Override
        public void run() throws Exception {
          Set<Long> live = new HashSet<Long>();
          live.addAll(Order.getLiveIDs(at, regionID, typeID));
          // Populate all orders
          for (Order next : orders) {
            // Record that this order is still live
            live.remove(next.getOrderID());
            // Construct new potential order
            Order check = new Order(
                regionID, typeID, next.getOrderID(), next.isBuy(), next.getIssued(), next.getPrice(), next.getVolumeEntered(), next.getMinVolume(),
                next.getVolume(), next.getOrderRange(), next.getLocationID(), next.getDuration());
            // Check whether we already know about this order
            Order existing = Order.get(at, regionID, typeID, check.getOrderID());
            // If the order exists and has changed, then evolve. Otherwise store the new order.
            if (existing != null) {
              // Existing, evolve if changed
              if (!existing.equivalent(check)) {
                // Evolve
                existing.evolve(check, at);
                Order.update(existing);
                Order.update(check);
              }
            } else {
              // New entity
              check.setup(at);
              Order.update(check);
            }
          }
          // End of life orders no longer present in the book
          for (Long orderID : live) {
            Order eol = Order.get(at, regionID, typeID, orderID);
            if (eol != null) {
              // NOTE: order may not longer exist if we're racing with another update
              eol.evolve(null, at);
              Order.update(eol);
            }
          }
        }
      });
    } catch (Exception e) {
      log.log(Level.SEVERE, "DB error storing order, failing: (" + regionID + ", " + typeID + ")", e);
      return Response.status(Status.INTERNAL_SERVER_ERROR).build();
    }
    long finish = OrbitalProperties.getCurrentTime();
    log.info("Finished processing for (" + regionID + ", " + typeID + ") in " + TimeUnit.SECONDS.convert(finish - at, TimeUnit.MILLISECONDS) + " seconds");
    // Order accepted
    return Response.ok().build();
  }

}