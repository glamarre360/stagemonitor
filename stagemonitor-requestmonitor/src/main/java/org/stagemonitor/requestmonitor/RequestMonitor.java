package org.stagemonitor.requestmonitor;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.stagemonitor.core.metrics.metrics2.MetricName.name;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Date;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stagemonitor.core.CorePlugin;
import org.stagemonitor.core.MeasurementSession;
import org.stagemonitor.core.Stagemonitor;
import org.stagemonitor.core.configuration.Configuration;
import org.stagemonitor.core.instrument.CallerUtil;
import org.stagemonitor.core.metrics.metrics2.Metric2Registry;
import org.stagemonitor.core.metrics.metrics2.MetricName;
import org.stagemonitor.core.util.ClassUtils;
import org.stagemonitor.core.util.CompletedFuture;
import org.stagemonitor.core.util.ExecutorUtils;
import org.stagemonitor.core.util.StringUtils;
import org.stagemonitor.requestmonitor.profiler.CallStackElement;
import org.stagemonitor.requestmonitor.profiler.Profiler;
import org.stagemonitor.requestmonitor.reporter.RequestTraceReporter;
import org.stagemonitor.requestmonitor.utils.IPAnonymizationUtils;

public class RequestMonitor {

	private static final Logger logger = LoggerFactory.getLogger(RequestMonitor.class);
	private static final MetricName.MetricNameTemplate errorRateTemplate = name("error_rate_server").tag("request_name", "").layer("All").templateFor("request_name");

	/**
	 * Helps to detect, if this request is the 'real' one or just the forwarding one.
	 * Example: /a is forwarding the request to /b. /a is the forwarding request /b is the real or forwarded request.
	 * Only /b will be measured, /a will be ignored.
	 * <p/>
	 * To enable this behaviour in a web environment, make sure to set stagemonitor.web.monitorOnlyForwardedRequests to true.
	 */
	// TODO remove static keyword. This is currently needed for tests
	private static ThreadLocal<RequestInformation<? extends RequestTrace>> request = new ThreadLocal<RequestInformation<? extends RequestTrace>>();

	private static MetricName.MetricNameTemplate timerMetricNameTemplate = name("response_time_server")
			.tag("request_name", "")
			.layer("All")
			.templateFor("request_name");

	private final List<RequestTraceReporter> requestTraceReporters = new CopyOnWriteArrayList<RequestTraceReporter>();

	private final List<Runnable> onBeforeRequestCallbacks = new CopyOnWriteArrayList<Runnable>();

	private final List<Runnable> onAfterRequestCallbacks = new CopyOnWriteArrayList<Runnable>();
	private final MetricName.MetricNameTemplate externalRequestRateTemplate = name("external_requests_rate").templateFor("request_name", "type");
	private final MetricName.MetricNameTemplate responseTimeExternalRequestLayerTemplate = name("response_time_server").templateFor("request_name", "layer");
	private final MetricName.MetricNameTemplate responseTimeCpuTemplate = name("response_time_cpu").tag("request_name", "").layer("All").templateFor("request_name");
	private final MetricName internalOverheadMetricName = name("internal_overhead_request_monitor").build();

	private ExecutorService asyncRequestTraceReporterPool;

	private int warmupRequests = 0;
	private AtomicBoolean warmedUp = new AtomicBoolean(false);
	private AtomicInteger noOfRequests = new AtomicInteger(0);
	private final Configuration configuration;
	private Metric2Registry metricRegistry;
	private CorePlugin corePlugin;
	private RequestMonitorPlugin requestMonitorPlugin;
	private ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
	private final boolean isCurrentThreadCpuTimeSupported = threadMXBean.isCurrentThreadCpuTimeSupported();
	private Date endOfWarmup;
	private Meter callTreeMeter = new Meter();

	public RequestMonitor(Configuration configuration, Metric2Registry registry) {
		this(configuration, registry, ServiceLoader.load(RequestTraceReporter.class, RequestMonitor.class.getClassLoader()));
	}

	public RequestMonitor(Configuration configuration, Metric2Registry registry, Iterable<RequestTraceReporter> requestTraceReporters) {
		this(configuration, registry, configuration.getConfig(RequestMonitorPlugin.class), requestTraceReporters);
	}

	private RequestMonitor(Configuration configuration, Metric2Registry registry, RequestMonitorPlugin requestMonitorPlugin,
						   Iterable<RequestTraceReporter> requestTraceReporters) {
		this.configuration = configuration;
		this.metricRegistry = registry;
		this.corePlugin = configuration.getConfig(CorePlugin.class);
		this.requestMonitorPlugin = requestMonitorPlugin;
		this.warmupRequests = requestMonitorPlugin.getNoOfWarmupRequests();
		this.endOfWarmup = new Date(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(requestMonitorPlugin.getWarmupSeconds()));
		this.asyncRequestTraceReporterPool = ExecutorUtils
				.createSingleThreadDeamonPool("async-request-reporter", corePlugin.getThreadPoolQueueCapacityLimit());
		for (RequestTraceReporter requestTraceReporter : requestTraceReporters) {
			addReporter(requestTraceReporter);
		}
	}

	public <T extends RequestTrace> void monitorStart(MonitoredRequest<T> monitoredRequest) {
		final long start = System.nanoTime();
		RequestInformation<T> info = new RequestInformation<T>();
		info.monitoredRequest = monitoredRequest;
		detectForwardedRequest(info);
		request.set(info);
		try {
			if (!corePlugin.isStagemonitorActive()) {
				return;
			}

			if (Stagemonitor.getMeasurementSession().isNull()) {
				createMeasurementSession();
			}

			info.firstRequest = noOfRequests.get() == 0;
			if (Stagemonitor.getMeasurementSession().getInstanceName() == null) {
				getInstanceNameFromExecution(monitoredRequest);
			}

			if (info.monitorThisRequest()) {
				if (!Stagemonitor.isStarted()) {
					info.startup = Stagemonitor.startMonitoring();
				}
				beforeExecution(monitoredRequest, info);
			}
		} finally {
			info.overhead1 = System.nanoTime() - start;
		}
	}

	public <T extends RequestTrace> void monitorStop() {
		long overhead2 = System.nanoTime();
		final RequestInformation<T> info = (RequestInformation<T>) request.get();
		request.set(info.parent);
		if (info.monitorThisRequest() && info.hasRequestName()) {
			try {
				if (info.startup != null) {
					info.startup.get();
				}
				monitorAfterExecution(info.monitoredRequest, info);
			} catch (Exception e) {
				logger.warn(e.getMessage() + " (this exception is ignored) " + info.toString(), e);
			}
		} else {
			removeTimerIfCountIsZero(info);
		}

		cleanUpAfter(info);

		if (!info.firstRequest) {
			trackOverhead(info.overhead1, overhead2);
		}

		for (Runnable onAfterRequestCallback : onAfterRequestCallbacks) {
			try {
				onAfterRequestCallback.run();
			} catch (RuntimeException e) {
				logger.warn(e.getMessage() + " (this exception is ignored) " + info.toString(), e);
			}
		}
	}

	private <T extends RequestTrace> void cleanUpAfter(RequestInformation<T> info) {
		if (info.requestTrace != null) {
			Profiler.clearMethodCallParent();
		}
	}

	public <T extends RequestTrace> RequestInformation<T> monitor(MonitoredRequest<T> monitoredRequest) throws Exception {
		try {
			monitorStart(monitoredRequest);
			final RequestInformation<T> info = (RequestInformation<T>) request.get();
			info.executionResult = monitoredRequest.execute();
			return info;
		} catch (Exception e) {
			recordException(e);
			throw e;
		} finally {
			monitorStop();
		}
	}

	public void recordException(Exception e) {
		final RequestInformation<? extends RequestTrace> info = request.get();
		if (info.requestTrace != null) {
			info.requestTrace.setException(e);
		}
	}

	private void trackOverhead(long overhead1, long overhead2) {
		if (corePlugin.isInternalMonitoringActive()) {
			overhead2 = System.nanoTime() - overhead2;
			metricRegistry.timer(internalOverheadMetricName).update(overhead2 + overhead1, NANOSECONDS);
		}
	}

	/*
	 * In case the instance name is not set by configuration, try to read from monitored execution
	 * (e.g. the domain name from a HTTP request)
	 */
	private synchronized void getInstanceNameFromExecution(MonitoredRequest<?> monitoredRequest) {
		final MeasurementSession measurementSession = Stagemonitor.getMeasurementSession();
		if (measurementSession.getInstanceName() == null) {
			MeasurementSession session = new MeasurementSession(measurementSession.getApplicationName(), measurementSession.getHostName(),
					monitoredRequest.getInstanceName());
			Stagemonitor.setMeasurementSession(session);
		}
	}

	private synchronized void createMeasurementSession() {
		if (Stagemonitor.getMeasurementSession().isNull()) {
			MeasurementSession session = new MeasurementSession(corePlugin.getApplicationName(), corePlugin.getHostName(),
					corePlugin.getInstanceName());
			Stagemonitor.setMeasurementSession(session);
		}
	}

	private <T extends RequestTrace> void beforeExecution(MonitoredRequest<T> monitoredRequest, RequestInformation<T> info) {
		info.requestTrace = monitoredRequest.createRequestTrace();
		try {
			if (info.isProfileThisRequest()) {
				callTreeMeter.mark();
				final CallStackElement root = Profiler.activateProfiling("total");
				info.requestTrace.setCallStack(root);
			}
		} catch (RuntimeException e) {
			logger.warn(e.getMessage() + " (this exception is ignored) " + info.toString(), e);
		}
		for (Runnable onBeforeRequestCallback : onBeforeRequestCallbacks) {
			try {
				onBeforeRequestCallback.run();
			} catch (RuntimeException e) {
				logger.warn(e.getMessage() + " (this exception is ignored) " + info.toString(), e);
			}
		}
	}

	private <T extends RequestTrace> void detectForwardedRequest(RequestInformation<T> info) {
		if (request.get() != null) {
			// there is already an request set in this thread -> this execution must have been forwarded
			info.parent = (RequestInformation<T>) request.get();
			info.parent.child = info;
		}
	}

	private <T extends RequestTrace> void monitorAfterExecution(MonitoredRequest<T> monitoredRequest, RequestInformation<T> info) {
		final T requestTrace = info.requestTrace;
		final long executionTime = System.nanoTime() - info.start;
		final long cpuTime = getCpuTime() - info.startCpu;
		requestTrace.setExecutionTime(NANOSECONDS.toMillis(executionTime));
		requestTrace.setExecutionTimeCpu(NANOSECONDS.toMillis(cpuTime));
		monitoredRequest.onPostExecute(info);
		anonymizeUserNameAndIp(requestTrace);

		if (requestTrace.getCallStack() != null) {
			Profiler.stop();
			requestTrace.getCallStack().setSignature(requestTrace.getName());
			final CallStackElement callTree = requestTrace.getCallStack();
			final double minExecutionTimeMultiplier = requestMonitorPlugin.getMinExecutionTimePercent() / 100;
			if (minExecutionTimeMultiplier > 0d) {
				callTree.removeCallsFasterThan((long) (callTree.getExecutionTime() * minExecutionTimeMultiplier));
			}
		}
		reportRequestTrace(info);
		trackMetrics(info, executionTime, cpuTime);
	}

	void anonymizeUserNameAndIp(RequestTrace requestTrace) {
		final String username = requestTrace.getUsername();
		if (requestMonitorPlugin.isPseudonymizeUserNames()) {
			requestTrace.setUsername(StringUtils.sha1Hash(username));
		}
		final boolean disclose = requestMonitorPlugin.getDiscloseUsers().contains(requestTrace.getUsername());
		if (disclose) {
			requestTrace.setDisclosedUserName(username);
		}
		if (requestTrace.getClientIp() != null && requestMonitorPlugin.isAnonymizeIPs() && !disclose) {
			requestTrace.setClientIp(IPAnonymizationUtils.anonymize(requestTrace.getClientIp()));
		}
	}

	private <T extends RequestTrace> void removeTimerIfCountIsZero(RequestInformation<T> info) {
		if (info.timerCreated) {
			MetricName timerMetricName = getTimerMetricName(info.getRequestName());
			if (info.getRequestTimer().getCount() == 0 && metricRegistry.getMetrics().get(timerMetricName) != null) {
				metricRegistry.remove(timerMetricName);
			}
		}
	}

	private <T extends RequestTrace> void trackMetrics(RequestInformation<T> info, long executionTime, long cpuTime) {
		T requestTrace = info.requestTrace;
		String requestName = info.getRequestName();

		info.getRequestTimer().update(executionTime, NANOSECONDS);
		metricRegistry.timer(getTimerMetricName("All")).update(executionTime, NANOSECONDS);

		if (requestMonitorPlugin.isCollectCpuTime()) {
			metricRegistry.timer(responseTimeCpuTemplate.build(requestName)).update(cpuTime, NANOSECONDS);
			metricRegistry.timer(responseTimeCpuTemplate.build("All")).update(cpuTime, NANOSECONDS);
		}

		if (requestTrace.isError()) {
			metricRegistry.meter(getErrorMetricName(requestName)).mark();
			metricRegistry.meter(getErrorMetricName("All")).mark();
		}
		trackExternalRequestMetrics(requestName, requestTrace);
	}

	public static MetricName getErrorMetricName(String requestName) {
		return errorRateTemplate.build(requestName);
	}

	private <T extends RequestTrace> void trackExternalRequestMetrics(String requestName, T requestTrace) {
		for (ExternalRequestStats externalRequestStats : requestTrace.getExternalRequestStats()) {
			if (externalRequestStats.getExecutionTimeNanos() > 0) {
				if (requestMonitorPlugin.isCollectDbTimePerRequest()) {
					metricRegistry.timer(responseTimeExternalRequestLayerTemplate
							.build(requestName, externalRequestStats.getRequestType()))
							.update(externalRequestStats.getExecutionTimeNanos(), NANOSECONDS);
				}
				metricRegistry.timer(responseTimeExternalRequestLayerTemplate
						.build("All", externalRequestStats.getRequestType()))
						.update(externalRequestStats.getExecutionTimeNanos(), NANOSECONDS);
			}
			// the difference to ElasticsearchExternalRequestReporter is that the
			// external_requests_rate is grouped by the request name, not the dao method name
			metricRegistry.meter(externalRequestRateTemplate
					.build(requestName, externalRequestStats.getRequestType()))
					.mark(externalRequestStats.getExecutionCount());
		}
	}

	public static MetricName getTimerMetricName(String requestName) {
		return timerMetricNameTemplate.build(requestName);
	}

	private <T extends RequestTrace> void reportRequestTrace(final RequestInformation<T> requestInformation) {
		try {
			requestInformation.requestTraceReporterFuture = asyncRequestTraceReporterPool.submit(new Runnable() {
				@Override
				public void run() {
					for (RequestTraceReporter requestTraceReporter : requestTraceReporters) {
						if (isActive(requestInformation.getRequestTrace(), requestTraceReporter)) {
							try {
								requestTraceReporter.reportRequestTrace(new RequestTraceReporter.ReportArguments(requestInformation.getRequestTrace()));
							} catch (Exception e) {
								logger.warn(e.getMessage() + " (this exception is ignored)", e);
							}
						}
					}
				}
			});
		} catch (RejectedExecutionException e) {
			requestInformation.requestTraceReporterFuture = new CompletedFuture<Object>(null);
			ExecutorUtils.logRejectionWarning(e);
		}
	}

	public boolean isWarmedUp() {
		if (!warmedUp.get()) {
			warmedUp.set(warmupRequests < noOfRequests.incrementAndGet() && new Date(System.currentTimeMillis() + 1).after(endOfWarmup));
			return warmedUp.get();
		} else {
			return true;
		}
	}

	private long getCpuTime() {
		return isCurrentThreadCpuTimeSupported ? threadMXBean.getCurrentThreadCpuTime() : 0L;
	}

	public class RequestInformation<T extends RequestTrace> {
		private boolean timerCreated = false;
		T requestTrace = null;
		private long start = System.nanoTime();
		private long startCpu = getCpuTime();
		private Object executionResult = null;
		private Future<?> startup;
		private long overhead1;
		private MonitoredRequest<T> monitoredRequest;
		private boolean firstRequest;
		private RequestInformation<T> parent;
		private RequestInformation<T> child;
		private Future<?> requestTraceReporterFuture;

		/**
		 * If the request has no name it means that it should not be monitored.
		 *
		 * @return <code>true</code>, if the request trace has a name, <code>false</code> otherwise
		 */
		private boolean hasRequestName() {
			return requestTrace != null && requestTrace.getName() != null && !requestTrace.getName().isEmpty();
		}

		private boolean monitorThisRequest() {
			if (!requestMonitorPlugin.isCollectRequestStats() || !isWarmedUp()) {
				return false;
			}

			if (isForwarded() && isForwarding()) {
				return false;
			}
			if (isForwarded()) {
				return monitoredRequest.isMonitorForwardedExecutions();
			}
			if (isForwarding()) {
				return !monitoredRequest.isMonitorForwardedExecutions();
			}
			return true;
		}

		public String getRequestName() {
			return requestTrace.getName();
		}

		/**
		 * Returns the request trace or <code>null</code>, if
		 * {@link org.stagemonitor.requestmonitor.RequestMonitor#isWarmedUp()} is <code>false</code>
		 *
		 * @return the request trace or <code>null</code>, if
		 *         {@link org.stagemonitor.requestmonitor.RequestMonitor#isWarmedUp()} is <code>false</code>
		 */
		public T getRequestTrace() {
			return requestTrace;
		}

		public Timer getRequestTimer() {
			timerCreated = true;
			return metricRegistry.timer(getTimerMetricName(getRequestName()));
		}

		private boolean isProfileThisRequest() {
			double callTreeRateLimit = requestMonitorPlugin.getOnlyCollectNCallTreesPerMinute();
			if (!requestMonitorPlugin.isProfilerActive()) {
				logger.debug("Not profiling this request because stagemonitor.profiler.active=false");
				return false;
			} else if (callTreeRateLimit <= 0) {
				logger.debug("Not profiling this request because stagemonitor.requestmonitor.onlyReportNRequestsPerMinuteToElasticsearch <= 0");
				return false;
			} else if (!isAnyRequestTraceReporterActiveWhichNeedsTheCallTree(getRequestTrace())) {
				logger.debug("Not profiling this request because no RequestTraceReporter is active {}", requestTraceReporters);
				return false;
			} else if (callTreeRateLimit < 1000000d && callTreeMeter.getOneMinuteRate() >= callTreeRateLimit) {
				logger.debug("Not profiling this request because more than {} call trees per minute where created", callTreeRateLimit);
				return false;
			}
			return true;
		}

		/**
		 * A forwarding request is one that forwards a request to another execution.
		 * <p/>
		 * Examples:
		 * <p/>
		 * - web environment: request to /a makes a
		 * {@link javax.servlet.RequestDispatcher#forward(ServletRequest, ServletResponse)} to /b.
		 * /a is the forwarding execution, /b is the forwarded execution
		 * <p/>
		 * - plain method calls: monitored method a() calls monitored method b()
		 * (monitored means monitored by {@link RequestMonitor}).
		 * Method a() is the forwarding execution, Method b() is the forwarded execution.
		 *
		 * @return true, if this request is a forwarding request, false otherwise
		 */
		private boolean isForwarding() {
			return child != null;
		}

		public Object getExecutionResult() {
			return executionResult;
		}

		@Override
		public String toString() {
			return "RequestInformation{" +
					"requestTrace=" + requestTrace +
					", start=" + start +
					", startCpu=" + startCpu +
					", forwardedExecution=" + isForwarded() +
					", executionResult=" + executionResult +
					'}';
		}

		public boolean isForwarded() {
			return parent != null;
		}

		public Future<?> getRequestTraceReporterFuture() {
			return requestTraceReporterFuture;
		}
	}

	private boolean isAnyRequestTraceReporterActiveWhichNeedsTheCallTree(RequestTrace requestTrace) {
		for (RequestTraceReporter reporter : requestTraceReporters) {
			if (reporter.requiresCallTree() && isActive(requestTrace, reporter)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks whether the given {@link RequestTraceReporter} is active for the current {@link RequestTrace}.
	 * If this method was already called for a {@link RequestTraceReporter} in the context of the current request
	 * it returns the previous result. In other words this method makes sure that {@link RequestTraceReporter#isActive(RequestTraceReporter.IsActiveArguments)}
	 * is called at most once.
	 */
	private boolean isActive(RequestTrace requestTrace, RequestTraceReporter requestTraceReporter) {
		final String requestAttributeActive = ClassUtils.getIdentityString(requestTraceReporter) + ".active";
		final Boolean activeFromAttribute = (Boolean) requestTrace.getRequestAttribute(requestAttributeActive);
		if (activeFromAttribute != null) {
			return activeFromAttribute;
		}
		final boolean active = requestTraceReporter.isActive(new RequestTraceReporter.IsActiveArguments(requestTrace));
		requestTrace.addRequestAttribute(requestAttributeActive, active);
		return active;
	}

	public RequestInformation<? extends RequestTrace> getRequestInfo(){
		return request.get();
	}

	public void setRequestInfo(RequestInformation<? extends RequestTrace> info){
		request.set(info);
	}

	/**
	 * @return the {@link RequestTrace} of the current request
	 * @deprecated use <code>RequestMonitor.get().getRequestTrace()</code> or
	 * <code>RequestMonitor.get().ifRequestPresent(RequestTraceConsumer)</code>
	 */
	@Deprecated
	public static RequestTrace getRequest() {
		final RequestInformation<? extends RequestTrace> requestInformation = RequestMonitor.get().request.get();
		return requestInformation != null ? requestInformation.getRequestTrace() : null;
	}

	/**
	 * @return the {@link RequestTrace} of the current request (may be <code>null</code>)
	 */
	public RequestTrace getRequestTrace() {
		final RequestInformation<? extends RequestTrace> requestInformation = request.get();
		return requestInformation != null ? requestInformation.getRequestTrace() : null;
	}

	/**
	 * Allows to modify the {@link RequestTrace} without explicit null checks.
	 * This method behaves simmilar to {@link java.util.Optional#ifPresent(java.util.function.Consumer)} and only applies the provided
	 * {@link RequestTraceConsumer} if there is a {@link RequestTrace} bound to the current thread.
	 * <p/>
	 * This method works well with lambdas and avoids verbose null checks.
	 *
	 * @param consumer a consumer which accepts the current {@link RequestTrace}
	 */
	public void ifRequestPresent(RequestTraceConsumer consumer) {
		final RequestTrace requestTrace = getRequestTrace();
		if (requestTrace != null) {
			consumer.accept(requestTrace);
		}
	}

	public interface RequestTraceConsumer {
		void accept(RequestTrace requestTrace);
	}

	/**
	 * Tracking an external request means that a timer for the execution time, grouped by the executing method
	 * {see @link #getCallerSignature()} is maintained.
	 * Additionally, the external requests may be stored in the stagemonitor-external-requests-* index, depending
	 * on the configuration.
	 *
	 * @param externalRequest the external request to track
	 */
	public void trackExternalRequest(ExternalRequest externalRequest) {
		final RequestTrace request = getRequestTrace();
		if (request != null) {
			externalRequest.setExecutedBy(CallerUtil.getCallerSignature());
			Profiler.addIOCall(externalRequest.getRequest(), externalRequest.getExecutionTimeNanos());
			request.addExternalRequest(externalRequest);
		}
	}

	/**
	 * Adds a {@link RequestTraceReporter}
	 *
	 * @param requestTraceReporter the {@link RequestTraceReporter} to add
	 */
	public static void addRequestTraceReporter(RequestTraceReporter requestTraceReporter) {
		get().addReporter(requestTraceReporter);
	}

	/**
	 * Gets the {@link RequestMonitor}.
	 * <p/>
	 * You can use this instance for example to call the following methods:
	 * <ul>
	 *     <li>{@link #trackExternalRequest(ExternalRequest)}</li>
	 *     <li>{@link #addReporter(RequestTraceReporter)}</li>
	 *     <li>{@link #addOnBeforeRequestCallback(Runnable)}</li>
	 *     <li>{@link #addOnAfterRequestCallback(Runnable)}</li>
	 *     <li>{@link #getRequestTrace()}</li>
	 *     <li>{@link #ifRequestPresent(RequestTraceConsumer)}</li>
	 * </ul>
	 *
	 * @return the current request monitor
	 */
	public static RequestMonitor get() {
		return Stagemonitor.getPlugin(RequestMonitorPlugin.class).getRequestMonitor();
	}

	/**
	 * Adds a {@link RequestTraceReporter}
	 *
	 * @param requestTraceReporter the {@link RequestTraceReporter} to add
	 */
	public void addReporter(RequestTraceReporter requestTraceReporter) {
		requestTraceReporters.add(0, requestTraceReporter);
		requestTraceReporter.init(new RequestTraceReporter.InitArguments(configuration));
	}

	public <T extends RequestTraceReporter> T getReporter(Class<T> reporterClass) {
		for (RequestTraceReporter requestTraceReporter : requestTraceReporters) {
			if (requestTraceReporter.getClass() == reporterClass) {
				return (T) requestTraceReporter;
			}
		}
		return null;
	}

	/**
	 * Shuts down the internal thread pool
	 */
	public void close() {
		asyncRequestTraceReporterPool.shutdown();
		request.remove();
	}

	/**
	 * Registers a callback that is called before a request gets executed
	 * <p/>
	 * This is a internal method. The API is likely to change!
	 *
	 * @param onBeforeRequestCallback The callback
	 */
	public void addOnBeforeRequestCallback(Runnable onBeforeRequestCallback) {
		onBeforeRequestCallbacks.add(onBeforeRequestCallback);
	}

	/**
	 * Registers a callback that is called after a request has been executed
	 * <p/>
	 * This is a internal method. The API is likely to change!
	 *
	 * @param onAfterRequestCallback The callback
	 */
	public void addOnAfterRequestCallback(Runnable onAfterRequestCallback) {
		onAfterRequestCallbacks.add(onAfterRequestCallback);
	}

	void setCallTreeMeter(Meter callTreeMeter) {
		this.callTreeMeter = callTreeMeter;
	}

}
