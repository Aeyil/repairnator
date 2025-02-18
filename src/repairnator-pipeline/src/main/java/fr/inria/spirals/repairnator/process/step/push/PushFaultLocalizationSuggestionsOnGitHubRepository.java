package fr.inria.spirals.repairnator.process.step.push;

import fr.inria.spirals.repairnator.config.RepairnatorConfig;
import fr.inria.spirals.repairnator.process.inspectors.GitRepositoryProjectInspector;
import fr.inria.spirals.repairnator.process.inspectors.ProjectInspector;
import fr.inria.spirals.repairnator.process.step.StepStatus;
import fr.inria.spirals.repairnator.states.PushState;
import fr.spoonlabs.flacoco.api.result.FlacocoResult;
import fr.spoonlabs.flacoco.api.result.Location;
import fr.spoonlabs.flacoco.api.result.Suspiciousness;
import org.apache.commons.lang.text.StrBuilder;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * It allows to push the Flacocobot results on a GitHub repository.
 */
public class PushFaultLocalizationSuggestionsOnGitHubRepository extends PushFaultLocalizationSuggestions {

    private static final Logger LOGGER = LoggerFactory.getLogger(PushFaultLocalizationSuggestionsOnGitHubRepository.class);

    private PushState pushState = null;
    private String pushSkippedReason = null;

    public PushFaultLocalizationSuggestionsOnGitHubRepository(ProjectInspector inspector, boolean blockingStep) {
        super(inspector, blockingStep);
    }

    @Override
    protected StepStatus businessExecute() {
        try {
            pushReviewComments(this.getInspector().getJobStatus().getFlacocoResult());
        } catch (IOException e) {
            this.getLogger().error(e.getLocalizedMessage());
            return StepStatus.buildSkipped(this, "There was an error while publishing fault localization results: " + e);
        }

        if (pushState == PushState.REPO_PUSHED) {
            return StepStatus.buildSuccess(this);
        } else {
            return StepStatus.buildSkipped(this, pushSkippedReason);
        }
    }

    private void pushReviewComments(FlacocoResult result) throws IOException {
        GitRepositoryProjectInspector githubInspector = (GitRepositoryProjectInspector) getInspector();
        GitHub gitHub = RepairnatorConfig.getInstance().getGithub();
        GHRepository originalRepository = gitHub.getRepository(githubInspector.getRepoSlug());
        GHPullRequest pullRequest = originalRepository.getPullRequest(githubInspector.getGitRepositoryPullRequest());

        Map<String, Map<Integer, Integer>> diffMapping = computeDiffMapping(pullRequest.getDiffUrl());
        List<String> suspiciousLinesCommentList = new ArrayList<>();

        int lines = 0;
        for (Map.Entry<Location, Suspiciousness> entry : result.getDefaultSuspiciousnessMap().entrySet()) {
            String partialFileName = entry.getKey().getClassName().replace(".", "/");
            Integer line = entry.getKey().getLineNumber();

            for (String fileName : diffMapping.keySet()) {

                // Since we don't have an exact mapping, we need to partially match them
                if (fileName.contains(partialFileName)) {

                    // We only consider the lines that are in the diff (i.e. that are mapped to a position in the diffMapping)
                    if (diffMapping.get(fileName).containsKey(line)) {
                        lines++;
                        suspiciousLinesCommentList.add(
                                String.format(
                                        "The line %d of the file " + fileName + " has been identified with a suspiciousness value of %,.2f%%.\n\n" +
                                                "<details>\n" +
                                                "     <summary>Failing tests that cover this line</summary>\n\n" +
                                                entry.getValue().getFailingTestCases().stream()
                                                        .map(x -> "- `" + x.getFullyQualifiedMethodName() + "`\n")
                                                        .reduce((x, y) -> x + y).orElse("{}") +
                                                "</details>"
                                        ,
                                        line,
                                        entry.getValue().getScore() * 100
                                )
                        );
                    }
                    break;
                }
            }

            // Break if we have reached the number of requested lines
            if (lines >= RepairnatorConfig.getInstance().getFlacocoTopK()) {
                break;
            }
        }

        String now = Instant.now().toString();

        if (lines > 0) {

            String message = "Add the suspicious lines contained in the diff for " + originalRepository.getName() + " PR #" + pullRequest.getNumber();

            String repositoryName = originalRepository.getFullName();
            int prNumber = pullRequest.getNumber();
            Date updatedAt = pullRequest.getUpdatedAt();
            String path = repositoryName + File.separator + prNumber + File.separator + "diff_"  + now + ".md";

            StrBuilder content = new StrBuilder();

            for (String suspiciousLineComment : suspiciousLinesCommentList) {
                content.append(suspiciousLineComment);
                content.appendNewLine().appendNewLine().append("***").appendNewLine().appendNewLine();
            }

            content.append("Project: ").append("[").append(repositoryName).append("]").append("(").
                    append(originalRepository.getHtmlUrl()).
                    append(")").appendNewLine();
            content.appendNewLine().append("Pull Request [#").append(pullRequest.getNumber()).append("](").
                    append(pullRequest.getHtmlUrl()).append(")").
                    append(" updated at: ").append(updatedAt);

            try {
                gitHub.getRepository(RepairnatorConfig.getInstance().getFlacocoResultsRepository()).
                        createContent().path(path).message(message).content(content.toString()).commit();
            } catch (IOException e) {
                e.printStackTrace();
                LOGGER.error("Localization information not saved on the specified GitHub repository");
                LOGGER.error(message + System.getProperty("line.separator") + content);
            }

            pushState = PushState.REPO_PUSHED;
        } else {
            this.getLogger().warn("Flacoco has found " + result.getDefaultSuspiciousnessMap().size() + " suspicious lines, but none were matched to the diff");
            pushSkippedReason = "Flacoco has found " + result.getDefaultSuspiciousnessMap().size() + " suspicious lines, but none were matched to the diff";
            pushState = PushState.REPO_NOT_PUSHED;
        }

        if (result.getDefaultSuspiciousnessMap().size() > 0) {

            List<String> suspiciousLinesCommentNotConsideringDiffList = new ArrayList<>();

            String message = "Add the suspicious lines for " + originalRepository.getName() + " PR #" + pullRequest.getNumber();

            String repositoryName = originalRepository.getFullName();
            int prNumber = pullRequest.getNumber();
            Date updatedAt = pullRequest.getUpdatedAt();
            String path = repositoryName + File.separator + prNumber + File.separator + now + ".md";

            int addedLines = 0;

            for (Map.Entry<Location, Suspiciousness> entry : result.getDefaultSuspiciousnessMap().entrySet()) {
                if (addedLines < RepairnatorConfig.getInstance().getFlacocoTopK()) {
                    suspiciousLinesCommentNotConsideringDiffList.add(
                            String.format(
                                    "The line %d of the file " + entry.getKey().getClassName().replace(".", "/") +
                                            " has been identified with a suspiciousness value of %,.2f%%.\n\n" +
                                            "<details>\n" +
                                            "     <summary>Failing tests that cover this line</summary>\n\n" +
                                            entry.getValue().getFailingTestCases().stream()
                                                    .map(x -> "- `" + x.getFullyQualifiedMethodName() + "`\n")
                                                    .reduce((x, y) -> x + y).orElse("{}") +
                                            "</details>"
                                    ,
                                    entry.getKey().getLineNumber(),
                                    entry.getValue().getScore() * 100
                            )
                    );
                    addedLines++;
                } else {
                    break;
                }
            }

            StrBuilder content = new StrBuilder();

            for (String suspiciousLineComment : suspiciousLinesCommentNotConsideringDiffList) {
                content.append(suspiciousLineComment);
                content.appendNewLine().appendNewLine().append("***").appendNewLine().appendNewLine();
            }

            content.append("Project: ").append("[").append(repositoryName).append("]").append("(").
                    append(originalRepository.getHtmlUrl()).append(")").appendNewLine();
            content.appendNewLine().append("Pull Request [#").append(pullRequest.getNumber()).append("](").
                    append(pullRequest.getHtmlUrl()).append(")").append(" updated at: ").append(updatedAt);

            try {
                gitHub.getRepository(RepairnatorConfig.getInstance().getFlacocoResultsRepository()).
                        createContent().path(path).message(message).content(content.toString()).commit();
            } catch (IOException e) {
                e.printStackTrace();
                LOGGER.error("Localization information not saved on the specified GitHub repository");
                LOGGER.error(message + System.getProperty("line.separator") + content);
            }
        }

        this.setPushState(pushState);
    }
}
