package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse.ContainerState;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the checks that never need a real Docker daemon. The parts that actually launch and
 * verify a container are proved end to end in
 * {@code EcsCredentialsProxyDockerIntegrationTest} instead: mocking the exec/callback plumbing
 * that {@link EcsCredentialsProxy#ensureProxyOn} depends on would mostly test the mock, not the
 * behaviour a runtime that silently drops {@code LinkLocalIPs} is meant to be caught by.
 */
class EcsCredentialsProxyTest {

    private DockerClient dockerClient;
    private ContainerBuilder containerBuilder;
    private ContainerLifecycleManager lifecycleManager;
    private EmulatorConfig config;
    private EcsCredentialsProxy proxy;

    @BeforeEach
    void setUp() {
        dockerClient = mock(DockerClient.class);
        containerBuilder = mock(ContainerBuilder.class);
        lifecycleManager = mock(ContainerLifecycleManager.class);
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        proxy = new EcsCredentialsProxy(dockerClient, containerBuilder, lifecycleManager, config);
    }

    @Test
    void rejectsABlankNetworkWithoutTouchingDocker() {
        assertThrows(IllegalStateException.class, () -> proxy.ensureProxyOn(null));
        assertThrows(IllegalStateException.class, () -> proxy.ensureProxyOn(""));
        assertThrows(IllegalStateException.class, () -> proxy.ensureProxyOn("   "));

        verify(dockerClient, never()).createContainerCmd(any());
        verify(containerBuilder, never()).newContainer(any());
    }

    @Test
    void skipsRelaunchingAProxyThatIsStillRunningOnTheSameNetwork() {
        stubSuccessfulLaunch("proxy-1");

        proxy.ensureProxyOn("floci-net");
        proxy.ensureProxyOn("floci-net");

        verify(lifecycleManager).createAndStart(any());
        verify(dockerClient).inspectContainerCmd("proxy-1");
        // The image check ran once, for the first launch; the second call short-circuits on
        // isRunning() before it ever reaches the image/launch path again.
        verify(dockerClient).inspectImageCmd(any());
    }

    @Test
    void stopManagedContainersRemovesEveryProxyThisInstanceLaunched() {
        stubSuccessfulLaunch("proxy-1");
        proxy.ensureProxyOn("floci-net");

        proxy.stopManagedContainers();

        verify(lifecycleManager).removeIfExists("proxy-1");
    }

    @Test
    void stopManagedContainersForgetsProxiesSoALaterEnsureRelaunchesInsteadOfShortCircuiting() {
        stubSuccessfulLaunch("proxy-1");
        proxy.ensureProxyOn("floci-net");
        proxy.stopManagedContainers();

        stubSuccessfulLaunch("proxy-2");
        proxy.ensureProxyOn("floci-net");

        // A full relaunch, not the isRunning() short-circuit skipsRelaunchingAProxy... covers:
        // stopManagedContainers must have actually forgotten "floci-net", not just removed the
        // container while still remembering it as the current one.
        verify(lifecycleManager, times(2)).createAndStart(any());
        verify(dockerClient, never()).inspectContainerCmd("proxy-2");
    }

    /**
     * Stubs the whole happy path for a single {@code ensureProxyOn} call to succeed against a
     * container with the given id: the image check, the launch, the running check for a repeat
     * call, and the endpoint-reachability exec/callback plumbing.
     */
    private void stubSuccessfulLaunch(String containerId) {
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(new ContainerSpec("floci/network-helper:local"));
        when(containerBuilder.newContainer(any())).thenReturn(builder);
        when(config.services().ecs().taskRoleCredentials().proxyImage()).thenReturn("floci/network-helper:local");
        when(containerBuilder.resolveImage("floci/network-helper:local")).thenReturn("floci/network-helper:local");
        com.github.dockerjava.api.command.InspectImageCmd inspectImageCmd =
                mock(com.github.dockerjava.api.command.InspectImageCmd.class);
        when(dockerClient.inspectImageCmd(any())).thenReturn(inspectImageCmd);
        when(inspectImageCmd.exec()).thenReturn(mock(com.github.dockerjava.api.command.InspectImageResponse.class));
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo(containerId, Map.of()));
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        InspectContainerResponse inspectResponse = mock(InspectContainerResponse.class);
        ContainerState running = mock(ContainerState.class);
        when(dockerClient.inspectContainerCmd(containerId)).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        when(inspectResponse.getState()).thenReturn(running);
        when(running.getRunning()).thenReturn(true);

        com.github.dockerjava.api.command.ExecCreateCmd execCreate =
                mock(com.github.dockerjava.api.command.ExecCreateCmd.class, RETURNS_SELF);
        com.github.dockerjava.api.command.ExecCreateCmdResponse execResponse =
                mock(com.github.dockerjava.api.command.ExecCreateCmdResponse.class);
        when(dockerClient.execCreateCmd(containerId)).thenReturn(execCreate);
        when(execCreate.exec()).thenReturn(execResponse);
        when(execResponse.getId()).thenReturn("exec-" + containerId);
        com.github.dockerjava.api.command.ExecStartCmd execStart =
                mock(com.github.dockerjava.api.command.ExecStartCmd.class);
        when(dockerClient.execStartCmd("exec-" + containerId)).thenReturn(execStart);
        when(execStart.exec(any())).thenAnswer(invocation -> {
            com.github.dockerjava.api.async.ResultCallback<com.github.dockerjava.api.model.Frame> callback =
                    invocation.getArgument(0);
            callback.onNext(new com.github.dockerjava.api.model.Frame(
                    com.github.dockerjava.api.model.StreamType.STDOUT,
                    "404".getBytes()));
            callback.onComplete();
            return callback;
        });
    }

    @Test
    void reapSurvivingProxiesRemovesOnlyThisInstancesOwnLeftovers() {
        when(config.docker().resourceNamespace()).thenReturn(java.util.Optional.empty());
        when(config.port()).thenReturn(4566);
        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        Container ownLeftover = mock(Container.class);
        when(ownLeftover.getId()).thenReturn("stale-proxy");
        when(ownLeftover.getLabels()).thenReturn(Map.of("floci_owner_port", "4566"));
        // Another Floci process sharing this daemon: same label, different owner. Reaping this
        // one would tear down a proxy a sibling instance is still actively using.
        Container otherInstance = mock(Container.class);
        when(otherInstance.getId()).thenReturn("other-instance-proxy");
        when(otherInstance.getLabels()).thenReturn(Map.of("floci_owner_port", "4567"));
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of(ownLeftover, otherInstance));

        proxy.reapSurvivingProxies();

        verify(lifecycleManager).removeIfExists("stale-proxy");
        verify(lifecycleManager, never()).removeIfExists("other-instance-proxy");
    }

    @Test
    void reapSurvivingProxiesToleratesADockerFailure() {
        when(dockerClient.listContainersCmd()).thenThrow(new RuntimeException("daemon unreachable"));

        proxy.reapSurvivingProxies();

        verify(lifecycleManager, never()).removeIfExists(any());
    }
}
