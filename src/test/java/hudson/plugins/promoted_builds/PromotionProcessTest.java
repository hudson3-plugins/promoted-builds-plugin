package hudson.plugins.promoted_builds;

import hudson.model.FreeStyleBuild;
import hudson.model.Result;
import hudson.model.FreeStyleProject;
import hudson.plugins.promoted_builds.conditions.DownstreamPassCondition;
import hudson.tasks.Recorder;
import hudson.tasks.ArtifactArchiver;
import hudson.tasks.BuildTrigger;
import hudson.tasks.Fingerprinter;
import hudson.tasks.Shell;
import java.util.Arrays;
import java.util.List;

/**
 * @author Kohsuke Kawaguchi
 */
public class PromotionProcessTest extends PromotionTestCase {
    public void test1() throws Exception {
        FreeStyleProject up = createFreeStyleProject("up");
        FreeStyleProject down = createFreeStyleProject();

        List<Recorder> recorders = Arrays.asList(
                new ArtifactArchiver("a.jar", null, false),
                new Fingerprinter("", true));

        // upstream job
        
        addBuilder(up, new Shell("date > a.jar"));
        replacePublishers(up, recorders);

        // promote if the downstream passes
        JobPropertyImpl promotion = new JobPropertyImpl(up);
        up.addProperty(promotion);
        PromotionProcess proc = promotion.addProcess("promo");
        proc.conditions.add(new DownstreamPassCondition(down.getName()));

        // this is the test job
        String baseUrl = new WebClient().getContextPath() + "job/up/lastSuccessfulBuild";
        addBuilder(down, new Shell(
            "wget -N "+baseUrl+"/artifact/a.jar \\\n"+
            "  || curl "+baseUrl+"/artifact/a.jar > a.jar\n"+
            "expr $BUILD_NUMBER % 2 - 1\n"  // expr exits with non-zero status if result is zero
        ));
        replacePublishers(down, recorders);

        // not yet promoted while the downstream is failing
        FreeStyleBuild up1 = assertBuildStatusSuccess(up.scheduleBuild2(0).get());
        assertBuildStatus(Result.FAILURE,down.scheduleBuild2(0).get());
        Thread.sleep(1000); // give it a time to not promote
        assertEquals(0,proc.getBuilds().size());

        // a successful downstream build promotes upstream
        assertBuildStatusSuccess(down.scheduleBuild2(0).get());
        Thread.sleep(1000); // give it a time to promote
        assertEquals(1,proc.getBuilds().size());

        {// verify that it promoted the right stuff
            Promotion pb = proc.getBuilds().get(0);
            assertSame(pb.getTarget(),up1);
            PromotedBuildAction badge = (PromotedBuildAction) up1.getBadgeActions().get(0);
            assertTrue(badge.contains(proc));
        }
    }
    
    /**
     * Tests a promotion induced by the pseudo upstream/downstream cause relationship
     */
    public void testPromotionWithoutFingerprint() throws Exception {
        FreeStyleProject up = createFreeStyleProject("up");
        FreeStyleProject down = createFreeStyleProject();

        // promote if the downstream passes
        JobPropertyImpl promotion = new JobPropertyImpl(up);
        up.addProperty(promotion);
        PromotionProcess proc = promotion.addProcess("promo");
        proc.conditions.add(new DownstreamPassCondition(down.getName()));

        // trigger downstream automatically to create relationship
        up.addPublisher(new BuildTrigger(down.getName(), Result.SUCCESS));
        hudson.rebuildDependencyGraph();
        
        // this is the downstream job
        addBuilder(down, new Shell(
            "expr $BUILD_NUMBER % 2 - 1\n"  // expr exits with non-zero status if result is zero
        ));

        // not yet promoted while the downstream is failing
        FreeStyleBuild up1 = assertBuildStatusSuccess(up.scheduleBuild2(0).get());
        waitForCompletion(down,1);
        assertEquals(0,proc.getBuilds().size());

        // do it one more time and this time it should work
        FreeStyleBuild up2 = assertBuildStatusSuccess(up.scheduleBuild2(0).get());
        waitForCompletion(down,2);
        assertEquals(1,proc.getBuilds().size());

        {// verify that it promoted the right stuff
            Promotion pb = proc.getBuilds().get(0);
            assertSame(pb.getTarget(),up2);
            PromotedBuildAction badge = (PromotedBuildAction) up2.getBadgeActions().get(0);
            assertTrue(badge.contains(proc));
        }
    }

    private void waitForCompletion(FreeStyleProject down, int n) throws InterruptedException {
        // wait for the build completion
        while (down.getBuildByNumber(n)==null)
            Thread.sleep(1000);
        while (down.getBuildByNumber(n).isBuilding())
            Thread.sleep(1000);
        Thread.sleep(1000); // give it a time to not promote
    }
}
