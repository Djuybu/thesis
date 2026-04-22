/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.sabot.exec;

import com.dremio.common.utils.protos.QueryIdHelper;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.planner.fragment.EndpointsIndex;
import com.dremio.exec.planner.fragment.PlanFragmentFull;
import com.dremio.exec.proto.CoordExecRPC.SchedulingInfo;
import com.dremio.exec.proto.UserBitShared.QueryId;
import com.dremio.options.OptionManager;
import com.dremio.sabot.exec.rpc.TunnelProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.annotation.concurrent.ThreadSafe;

/** Manages workload and query level allocators */
@ThreadSafe
public class QueriesClerk {

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(QueriesClerk.class);
  private final WorkloadTicketDepot workloadTicketDepot;

  private final ReservedResourceDepot reservedResourceDepot;
  private final Collection<DynamicReservedResource> resources = new ConcurrentLinkedQueue<>();
  private boolean enforceDLRReservations;
  private final OptionManager optionManager;

  QueriesClerk(
      final WorkloadTicketDepot workloadTicketDepot,
      final ReservedResourceDepot reservedResourceDepot,
      final boolean enforceDLRReservations) {
    this.workloadTicketDepot = workloadTicketDepot;
    this.reservedResourceDepot = reservedResourceDepot;
    this.enforceDLRReservations = enforceDLRReservations;
    this.optionManager = null;
  }

  QueriesClerk(
      final WorkloadTicketDepot workloadTicketDepot,
      final ReservedResourceDepot reservedResourceDepot,
      final OptionManager optionManager) {
    this.workloadTicketDepot = workloadTicketDepot;
    this.reservedResourceDepot = reservedResourceDepot;
    this.enforceDLRReservations = false;
    this.optionManager = optionManager;
  }

  public boolean getEnforceDLRReservations() {
    if (this.optionManager != null) {
      enforceDLRReservations =
          this.optionManager.getOption(ExecConstants.DYNAMIC_LOAD_ROUTING_ENFORCE_RESERVATIONS);
    }
    return enforceDLRReservations;
  }

  public long getAmplifyFactor() {
    if (this.optionManager == null) {
      return 1;
    }
    return this.optionManager.getOption(
        ExecConstants.DYNAMIC_LOAD_ROUTING_NON_SPILLABLE_MEMORY_AMPLIFY_FACTOR);
  }

  /**
   * Builds and starts a new query, if sufficient resources are available. In case resources are not
   * available immediately, the query will be started later, when resources become available
   */
  public void buildAndStartQuery(
      final PlanFragmentFull firstFragment,
      final SchedulingInfo schedulingInfo,
      final QueryStarter queryStarter) {
    final QueryId queryId = firstFragment.getHandle().getQueryId();

    // Note: We temporarily hold a reference count on the workload ticket (released in the
    // `finally` clause, below), the call to reserve is in `getWorkloadTicket`. The reference
    // is necessary to guard against races between potential workload ticket modifications
    // and this function (creation of fragments for queries on the workload)
    WorkloadTicket workloadTicket = workloadTicketDepot.getWorkloadTicket(schedulingInfo);
    try {
      final long queryMaxAllocation =
          workloadTicket.getChildMaxAllocation(
              firstFragment.getMajor().getContext().getQueryMaxAllocation());

      workloadTicket.buildAndStartQuery(
          queryId,
          queryMaxAllocation,
          firstFragment.getMajor().getForeman(),
          firstFragment.getMinor().getAssignment(),
          queryStarter,
          this);
    } finally {
      workloadTicket.release();
    }
  }

  /**
   * creates a fragment ticket for the passed-in fragment. May create a new query ticket if no query
   * ticket is already cached. Closing the ticket will return the reservation and eventually close
   * the corresponding query ticket along with its allocator
   *
   * @param queryTicket the query ticket, obtained from the callback from {@link
   *     #buildAndStartQuery(PlanFragmentFull, SchedulingInfo, QueryStarter)}
   * @param fragment fragment plan
   * @param schedulingInfo information about where should 'fragment' run
   * @return reserved query allocator
   */
  public FragmentTicket newFragmentTicket(
      final QueryTicket queryTicket,
      final PlanFragmentFull fragment,
      final SchedulingInfo schedulingInfo) {
    // Note: applying query limit to the phase, as that doesn't add any additional restrictions. If
    // an when we have
    // phase limits on the plan fragment, we could apply them here.
    PhaseTicket phaseTicket =
        queryTicket.getOrCreatePhaseTicket(
            fragment.getHandle().getMajorFragmentId(),
            queryTicket.getAllocator().getLimit(),
            fragment.getMajor().getFragmentExecWeight());
    return new FragmentTicket(phaseTicket, fragment.getHandle(), queryTicket.getSchedulingGroup());
  }

  /**
   * @return all the active query tickets
   */
  Collection<WorkloadTicket> getWorkloadTickets() {
    return workloadTicketDepot.getWorkloadTickets();
  }

  /**
   * Gets all the fragment tickets associated with a query.
   *
   * @param queryId Query ID
   * @return collection of fragment tickets
   */
  Collection<FragmentTicket> getFragmentTickets(QueryId queryId) {
    List<FragmentTicket> fragmentTickets = new ArrayList<>();

    for (WorkloadTicket workloadTicket : getWorkloadTickets()) {
      QueryTicket queryTicket = workloadTicket.getQueryTicket(queryId);
      if (queryTicket != null) {
        for (PhaseTicket phaseTicket : queryTicket.getActivePhaseTickets()) {
          fragmentTickets.addAll(phaseTicket.getFragmentTickets());
        }

        // found the query ticket.
        break;
      }
    }
    return fragmentTickets;
  }

  public void notifyFinishedQuery(QueryTicket queryTicket, WorkloadTicket workloadTicket)
      throws Exception {
    workloadTicket.removeQueryTicket(queryTicket);

    reservedResourceDepot
        .getReservedResources()
        .forEach((resource) -> resource.release(queryTicket.getQueryId()));
  }

  public void reserveResources(QueryId queryId) throws Exception {
    for (DynamicReservedResource resource : reservedResourceDepot.getReservedResources()) {
      try {
        resource.reserve(queryId);
      } catch (Exception e) {
        ExecutionMetrics.getDLRFailedReservations().withTags("resource", resource.toString());
        if (getEnforceDLRReservations()) {
          throw e;
        }
      }
    }
  }

  public void releaseResources(QueryId queryId) {
    reservedResourceDepot.getReservedResources().forEach((resource) -> resource.release(queryId));
  }

  public void recordResourceEstimates(
      QueryTicket queryTicket, List<PlanFragmentFull> fullFragments) {
    reservedResourceDepot
        .getReservedResources()
        .forEach((resource) -> resource.estimate(queryTicket, fullFragments));
  }

  TunnelProvider getTunnelProvider(QueryId queryId) {
    for (WorkloadTicket workloadTicket : getWorkloadTickets()) {
      QueryTicket queryTicket = workloadTicket.getQueryTicket(queryId);
      if (queryTicket != null) {
        return queryTicket.getTunnelProvider();
      }
    }
    return null;
  }

  EndpointsIndex getEndpointsIndex(QueryId queryId) {
    for (WorkloadTicket workloadTicket : getWorkloadTickets()) {
      QueryTicket queryTicket = workloadTicket.getQueryTicket(queryId);
      if (queryTicket != null) {
        return queryTicket.getEndpointsIndex();
      }
    }
    logger.error("queryTicket is null for queryId {}", QueryIdHelper.getQueryId(queryId));
    return null;
  }
}
