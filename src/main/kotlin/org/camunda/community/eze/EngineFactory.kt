package org.camunda.community.eze

import io.camunda.zeebe.db.ZeebeDb
import io.camunda.zeebe.engine.processing.EngineProcessors
import io.camunda.zeebe.engine.processing.streamprocessor.StreamProcessor
import io.camunda.zeebe.engine.state.DefaultZeebeDbFactory
import io.camunda.zeebe.engine.state.ZbColumnFamilies
import io.camunda.zeebe.engine.state.appliers.EventAppliers
import io.camunda.zeebe.logstreams.log.LogStream
import io.camunda.zeebe.logstreams.storage.LogStorage
import io.camunda.zeebe.util.sched.Actor
import io.camunda.zeebe.util.sched.ActorScheduler
import io.camunda.zeebe.util.sched.ActorSchedulingService
import io.camunda.zeebe.util.sched.clock.ActorClock
import io.camunda.zeebe.util.sched.clock.ControlledActorClock
import io.grpc.ServerBuilder
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

object EngineFactory {

    private val subscriptionCommandSenderFactory = SubscriptionCommandSenderFactory { partitionId ->
        TODO("return record writer")
    }

    fun create(): ZeebeEngine {

        val clock = createActorClock()
        val scheduler = createActorScheduler(clock)

        val logStorage = createLogStorage()
        val logStream = createLogStream(
            partitionId = 1,
            logStorage = logStorage,
            scheduler = scheduler
        )


        val streamWriter = logStream.newLogStreamRecordWriter().join()
        val simpleGateway = SimpleGateway(streamWriter)
        val server = ServerBuilder.forPort(26500).addService(simpleGateway).build()
        server.start()

        val db = createDatabase()

        val responseCallback = simpleGateway::responseCallback
        val grpcResponseWriter = GrpcResponseWriter(responseCallback)

        val streamProcessor = createStreamProcessor(
            partitionCount = 1,
            logStream = logStream,
            database = db,
            scheduler = scheduler,
            grpcResponseWriter
        )

        streamProcessor.openAsync(false).join()

        return ZeebeEngineImpl()
    }

    private fun createStreamProcessor(
        partitionCount: Int,
        logStream: LogStream,
        database: ZeebeDb<ZbColumnFamilies>,
        scheduler: ActorSchedulingService,
        grpcResponseWriter: GrpcResponseWriter
    ): StreamProcessor {
        return StreamProcessor.builder()
            .logStream(logStream)
            .zeebeDb(database)
            .eventApplierFactory { EventAppliers(it) }
            .commandResponseWriter(grpcResponseWriter)
            .streamProcessorFactory { context ->
                EngineProcessors.createEngineProcessors(
                    context,
                    partitionCount,
                    subscriptionCommandSenderFactory.ofPartition(partitionId = 1),
                    SinglePartitionDeploymentDistributor(),
                    SinglePartitionDeploymentResponder(),
                    { jobType ->
                        // new job is available
                    }
                )
            }
            .actorSchedulingService(scheduler)
            .build()
    }

    private fun createDatabase(): ZeebeDb<ZbColumnFamilies> {
        val zeebeDbFactory = DefaultZeebeDbFactory.defaultFactory()
        return zeebeDbFactory.createDb(Files.createTempDirectory("zeebeDb").toFile())
    }

    private fun createLogStream(
        partitionId: Int,
        logStorage: LogStorage,
        scheduler: ActorSchedulingService
    ): LogStream {
        val builder = LogStream.builder()
            .withPartitionId(partitionId)
            .withLogStorage(logStorage)
            .withActorSchedulingService(scheduler)

        val theFuture = CompletableFuture<LogStream>()

        scheduler.submitActor(Actor.wrap {
            builder
                .buildAsync()
                .onComplete { logStream, failure ->
                    // TODO boom
                    theFuture.complete(logStream)
                }
        })

        return theFuture.join()
    }

    private fun createLogStorage(): LogStorage {
        return InMemoryLogStorage()
    }

    private fun createActorScheduler(clock: ActorClock): ActorSchedulingService {
        val scheduler = ActorScheduler.newActorScheduler()
            .setActorClock(clock)
            .build()

        scheduler.start()

        return scheduler
    }

    private fun createActorClock(): ControlledActorClock {
        return ControlledActorClock()
    }

}
