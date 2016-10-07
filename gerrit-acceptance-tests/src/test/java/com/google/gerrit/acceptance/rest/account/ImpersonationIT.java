// Copyright (C) 2016 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.acceptance.rest.account;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.server.group.SystemGroupBackend.ANONYMOUS_USERS;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.GerritConfig;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.RestSession;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.common.data.LabelType;
import com.google.gerrit.common.data.Permission;
import com.google.gerrit.extensions.api.changes.DraftInput;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewInput.CommentInput;
import com.google.gerrit.extensions.api.changes.ReviewInput.DraftHandling;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.api.changes.SubmitInput;
import com.google.gerrit.extensions.api.groups.GroupInput;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.client.Side;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.common.ApprovalInfo;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeMessageInfo;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.extensions.common.GroupInfo;
import com.google.gerrit.extensions.common.LabelInfo;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.reviewdb.client.Patch;
import com.google.gerrit.server.account.AccountControl;
import com.google.gerrit.server.git.ProjectConfig;
import com.google.gerrit.server.group.SystemGroupBackend;
import com.google.gerrit.server.project.Util;
import com.google.inject.Inject;

import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ImpersonationIT extends AbstractDaemonTest {
  @Inject
  private AccountControl.Factory accountControlFactory;

  private RestSession anonRestSession;
  private TestAccount admin2;
  private GroupInfo newGroup;

  @Before
  public void setUp() throws Exception {
    anonRestSession = new RestSession(server, null);
    admin2 = accounts.admin2();
    GroupInput gi = new GroupInput();
    gi.name = name("New-Group");
    gi.members = ImmutableList.of(user.id.toString());
    newGroup = gApi.groups().create(gi).get();
  }

  @After
  public void tearDown() throws Exception {
    removeRunAs();
  }

  @Test
  public void voteOnBehalfOf() throws Exception {
    allowCodeReviewOnBehalfOf();
    PushOneCommit.Result r = createChange();
    RevisionApi revision = gApi.changes()
        .id(r.getChangeId())
        .current();

    ReviewInput in = ReviewInput.recommend();
    in.onBehalfOf = user.id.toString();
    in.message = "Message on behalf of";
    revision.review(in);

    ChangeInfo c = gApi.changes()
        .id(r.getChangeId())
        .get();

    LabelInfo codeReview = c.labels.get("Code-Review");
    assertThat(codeReview.all).hasSize(1);
    ApprovalInfo approval = codeReview.all.get(0);
    assertThat(approval._accountId).isEqualTo(user.id.get());
    assertThat(approval.value).isEqualTo(1);

    ChangeMessageInfo m = Iterables.getLast(c.messages);
    assertThat(m.message).endsWith(in.message);
    assertThat(m.author._accountId).isEqualTo(user.id.get());
  }

  @Test
  public void voteOnBehalfOfRequiresLabel() throws Exception {
    allowCodeReviewOnBehalfOf();
    PushOneCommit.Result r = createChange();
    RevisionApi revision = gApi.changes()
        .id(r.getChangeId())
        .current();

    ReviewInput in = new ReviewInput();
    in.onBehalfOf = user.id.toString();
    in.message = "Message on behalf of";

    exception.expect(AuthException.class);
    exception.expectMessage(
        "label required to post review on behalf of \"" + in.onBehalfOf + '"');
    revision.review(in);
  }

  @Test
  public void voteOnBehalfOfInvalidLabel() throws Exception {
    allowCodeReviewOnBehalfOf();
    PushOneCommit.Result r = createChange();
    RevisionApi revision = gApi.changes()
        .id(r.getChangeId())
        .current();

    ReviewInput in = new ReviewInput();
    in.onBehalfOf = user.id.toString();
    in.strictLabels = true;
    in.label("Not-A-Label", 5);

    exception.expect(BadRequestException.class);
    exception.expectMessage(
        "label \"Not-A-Label\" is not a configured label");
    revision.review(in);
  }

  @Test
  public void voteOnBehalfOfInvalidLabelIgnoredWithoutStrictLabels()
      throws Exception {
    allowCodeReviewOnBehalfOf();
    PushOneCommit.Result r = createChange();
    RevisionApi revision = gApi.changes()
        .id(r.getChangeId())
        .current();

    ReviewInput in = new ReviewInput();
    in.onBehalfOf = user.id.toString();
    in.strictLabels = false;
    in.label("Code-Review", 1);
    in.label("Not-A-Label", 5);

    revision.review(in);

    assertThat(gApi.changes().id(r.getChangeId()).get().labels)
        .doesNotContainKey("Not-A-Label");
  }

  @Test
  public void voteOnBehalfOfLabelNotPermitted() throws Exception {
    ProjectConfig cfg = projectCache.checkedGet(project).getConfig();
    LabelType verified = Util.verified();
    cfg.getLabelSections().put(verified.getName(), verified);
    saveProjectConfig(project, cfg);

    PushOneCommit.Result r = createChange();
    RevisionApi revision = gApi.changes()
        .id(r.getChangeId())
        .current();

    ReviewInput in = new ReviewInput();
    in.onBehalfOf = user.id.toString();
    in.label("Verified", 1);

    exception.expect(AuthException.class);
    exception.expectMessage(
        "not permitted to modify label \"Verified\" on behalf of \""
            + in.onBehalfOf + '"');
    revision.review(in);
  }

  @Test
  public void voteOnBehalfOfWithComment() throws Exception {
    allowCodeReviewOnBehalfOf();
    PushOneCommit.Result r = createChange();

    ReviewInput in = new ReviewInput();
    in.onBehalfOf = user.id.toString();
    in.label("Code-Review", 1);
    CommentInput ci = new CommentInput();
    ci.path = Patch.COMMIT_MSG;
    ci.side = Side.REVISION;
    ci.line = 1;
    ci.message = "message";
    in.comments = ImmutableMap.of(ci.path, ImmutableList.of(ci));

    gApi.changes().id(r.getChangeId()).current().review(in);
    CommentInfo c = Iterables.getOnlyElement(
        gApi.changes().id(r.getChangeId()).current().commentsAsList());
    assertThat(c.author._accountId).isEqualTo(user.id.get());
    assertThat(c.message).isEqualTo(ci.message);
  }

  @Test
  public void voteOnBehalfOfCannotModifyDrafts() throws Exception {
    allowCodeReviewOnBehalfOf();
    PushOneCommit.Result r = createChange();

    setApiUser(user);
    DraftInput di = new DraftInput();
    di.path = Patch.COMMIT_MSG;
    di.side = Side.REVISION;
    di.line = 1;
    di.message = "message";
    gApi.changes().id(r.getChangeId()).current().createDraft(di);

    setApiUser(admin);
    ReviewInput in = new ReviewInput();
    in.onBehalfOf = user.id.toString();
    in.label("Code-Review", 1);
    in.drafts = DraftHandling.PUBLISH;

    exception.expect(AuthException.class);
    exception.expectMessage("not allowed to modify other user's drafts");
    gApi.changes().id(r.getChangeId()).current().review(in);
  }

  @Test
  public void voteOnBehalfOfMissingUser() throws Exception {
    allowCodeReviewOnBehalfOf();
    PushOneCommit.Result r = createChange();
    RevisionApi revision = gApi.changes()
        .id(r.getChangeId())
        .current();

    ReviewInput in = new ReviewInput();
    in.onBehalfOf = "doesnotexist";
    in.label("Code-Review", 1);

    exception.expect(UnprocessableEntityException.class);
    exception.expectMessage("Account Not Found: doesnotexist");
    revision.review(in);
  }

  @Test
  public void voteOnBehalfOfFailsWhenUserCannotSeeDestinationRef()
      throws Exception {
    blockRead(newGroup);

    allowCodeReviewOnBehalfOf();
    PushOneCommit.Result r = createChange();
    RevisionApi revision = gApi.changes()
        .id(r.getChangeId())
        .current();

    ReviewInput in = new ReviewInput();
    in.onBehalfOf = user.id.toString();
    in.label("Code-Review", 1);

    exception.expect(UnprocessableEntityException.class);
    exception.expectMessage(
        "on_behalf_of account " + user.id + " cannot see destination ref");
    revision.review(in);
  }

  @GerritConfig(name = "accounts.visibility", value = "SAME_GROUP")
  @Test
  public void voteOnBehalfOfInvisibleUserNotAllowed() throws Exception {
    allowCodeReviewOnBehalfOf();
    setApiUser(accounts.user2());
    assertThat(accountControlFactory.get().canSee(user.id)).isFalse();

    PushOneCommit.Result r = createChange();
    RevisionApi revision = gApi.changes()
        .id(r.getChangeId())
        .current();

    ReviewInput in = new ReviewInput();
    in.onBehalfOf = user.id.toString();
    in.label("Code-Review", 1);

    exception.expect(UnprocessableEntityException.class);
    exception.expectMessage("Account Not Found: " + in.onBehalfOf);
    revision.review(in);
  }

  @Test
  public void submitOnBehalfOf() throws Exception {
    allowSubmitOnBehalfOf();
    PushOneCommit.Result r = createChange();
    String changeId = project.get() + "~master~" + r.getChangeId();
    gApi.changes()
        .id(changeId)
        .current()
        .review(ReviewInput.approve());
    SubmitInput in = new SubmitInput();
    in.onBehalfOf = admin2.email;
    gApi.changes()
        .id(changeId)
        .current()
        .submit(in);
    assertThat(gApi.changes().id(changeId).get().status)
        .isEqualTo(ChangeStatus.MERGED);
  }

  @Test
  public void submitOnBehalfOfInvalidUser() throws Exception {
    allowSubmitOnBehalfOf();
    PushOneCommit.Result r = createChange();
    String changeId = project.get() + "~master~" + r.getChangeId();
    gApi.changes()
        .id(changeId)
        .current()
        .review(ReviewInput.approve());
    SubmitInput in = new SubmitInput();
    in.onBehalfOf = "doesnotexist";
    exception.expect(UnprocessableEntityException.class);
    exception.expectMessage("Account Not Found: doesnotexist");
    gApi.changes()
        .id(changeId)
        .current()
        .submit(in);
  }

  @Test
  public void submitOnBehalfOfNotPermitted() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes()
        .id(project.get() + "~master~" + r.getChangeId())
        .current()
        .review(ReviewInput.approve());
    SubmitInput in = new SubmitInput();
    in.onBehalfOf = admin2.email;
    exception.expect(AuthException.class);
    exception.expectMessage("submit on behalf of not permitted");
    gApi.changes()
        .id(project.get() + "~master~" + r.getChangeId())
        .current()
        .submit(in);
  }

  @Test
  public void submitOnBehalfOfFailsWhenUserCannotSeeDestinationRef()
      throws Exception {
    blockRead(newGroup);

    allowSubmitOnBehalfOf();
    PushOneCommit.Result r = createChange();
    String changeId = project.get() + "~master~" + r.getChangeId();
    gApi.changes()
        .id(changeId)
        .current()
        .review(ReviewInput.approve());
    SubmitInput in = new SubmitInput();
    in.onBehalfOf = user.email;
    exception.expect(UnprocessableEntityException.class);
    exception.expectMessage(
        "on_behalf_of account " + user.id + " cannot see destination ref");
    gApi.changes()
        .id(changeId)
        .current()
        .submit(in);
  }

  @GerritConfig(name = "accounts.visibility", value = "SAME_GROUP")
  @Test
  public void submitOnBehalfOfInvisibleUserNotAllowed() throws Exception {
    allowSubmitOnBehalfOf();
    setApiUser(accounts.user2());
    assertThat(accountControlFactory.get().canSee(user.id)).isFalse();

    PushOneCommit.Result r = createChange();
    String changeId = project.get() + "~master~" + r.getChangeId();
    gApi.changes()
        .id(changeId)
        .current()
        .review(ReviewInput.approve());
    SubmitInput in = new SubmitInput();
    in.onBehalfOf = user.email;
    exception.expect(UnprocessableEntityException.class);
    exception.expectMessage("Account Not Found: " + in.onBehalfOf);
    gApi.changes()
        .id(changeId)
        .current()
        .submit(in);
  }

  @Test
  public void runAsValidUser() throws Exception {
    allowRunAs();
    RestResponse res =
        adminRestSession.getWithHeader("/accounts/self", runAsHeader(user.id));
    res.assertOK();
    AccountInfo account =
        newGson().fromJson(res.getEntityContent(), AccountInfo.class);
    assertThat(account._accountId).isEqualTo(user.id.get());
  }

  @GerritConfig(name = "auth.enableRunAs", value = "false")
  @Test
  public void runAsDisabledByConfig() throws Exception {
    allowRunAs();
    RestResponse res =
        adminRestSession.getWithHeader("/changes/", runAsHeader(user.id));
    res.assertForbidden();
    assertThat(res.getEntityContent())
        .isEqualTo("X-Gerrit-RunAs disabled by auth.enableRunAs = false");
  }

  @Test
  public void runAsNotPermitted() throws Exception {
    RestResponse res =
        adminRestSession.getWithHeader("/changes/", runAsHeader(user.id));
    res.assertForbidden();
    assertThat(res.getEntityContent())
        .isEqualTo("not permitted to use X-Gerrit-RunAs");
  }

  @Test
  public void runAsNeverPermittedForAnonymousUsers() throws Exception {
    allowRunAs();
    RestResponse res =
        anonRestSession.getWithHeader("/changes/", runAsHeader(user.id));
    res.assertForbidden();
    assertThat(res.getEntityContent())
        .isEqualTo("not permitted to use X-Gerrit-RunAs");
  }

  @Test
  public void runAsInvalidUser() throws Exception {
    allowRunAs();
    RestResponse res = adminRestSession.getWithHeader(
        "/changes/", runAsHeader("doesnotexist"));
    res.assertForbidden();
    assertThat(res.getEntityContent())
        .isEqualTo("no account matches X-Gerrit-RunAs");
  }

  @Test
  public void voteUsingRunAsAvoidsRestrictionsOfOnBehalfOf() throws Exception {
    allowRunAs();
    PushOneCommit.Result r = createChange();

    setApiUser(user);
    DraftInput di = new DraftInput();
    di.path = Patch.COMMIT_MSG;
    di.side = Side.REVISION;
    di.line = 1;
    di.message = "inline comment";
    gApi.changes().id(r.getChangeId()).current().createDraft(di);
    setApiUser(admin);

    // Things that aren't allowed with on_behalf_of:
    //  - no labels.
    //  - publish other user's drafts.
    ReviewInput in = new ReviewInput();
    in.message = "message";
    in.drafts = DraftHandling.PUBLISH;
    RestResponse res = adminRestSession.postWithHeader(
        "/changes/" + r.getChangeId() + "/revisions/current/review", in,
        runAsHeader(user.id));
    res.assertOK();

    ChangeMessageInfo m = Iterables.getLast(
        gApi.changes().id(r.getChangeId()).get().messages);
    assertThat(m.message).endsWith(in.message);
    assertThat(m.author._accountId).isEqualTo(user.id.get());

    CommentInfo c = Iterables.getOnlyElement(
        gApi.changes().id(r.getChangeId()).comments().get(di.path));
    assertThat(c.author._accountId).isEqualTo(user.id.get());
    assertThat(c.message).isEqualTo(di.message);

    setApiUser(user);
    assertThat(gApi.changes().id(r.getChangeId()).drafts()).isEmpty();
  }

  private void allowCodeReviewOnBehalfOf() throws Exception {
    ProjectConfig cfg = projectCache.checkedGet(project).getConfig();
    LabelType codeReviewType = Util.codeReview();
    String forCodeReviewAs = Permission.forLabelAs(codeReviewType.getName());
    String heads = "refs/heads/*";
    AccountGroup.UUID uuid =
        SystemGroupBackend.getGroup(REGISTERED_USERS).getUUID();
    Util.allow(cfg, forCodeReviewAs, -1, 1, uuid, heads);
    saveProjectConfig(project, cfg);
  }

  private void allowSubmitOnBehalfOf() throws Exception {
    ProjectConfig cfg = projectCache.checkedGet(project).getConfig();
    String heads = "refs/heads/*";
    AccountGroup.UUID uuid =
        SystemGroupBackend.getGroup(REGISTERED_USERS).getUUID();
    Util.allow(cfg, Permission.SUBMIT_AS, uuid, heads);
    Util.allow(cfg, Permission.SUBMIT, uuid, heads);
    LabelType codeReviewType = Util.codeReview();
    Util.allow(cfg, Permission.forLabel(codeReviewType.getName()),
        -2, 2, uuid, heads);
    saveProjectConfig(project, cfg);
  }

  private void blockRead(GroupInfo group) throws Exception {
    ProjectConfig cfg = projectCache.checkedGet(project).getConfig();
    Util.block(
        cfg, Permission.READ, new AccountGroup.UUID(group.id), "refs/heads/master");
    saveProjectConfig(project, cfg);
  }

  private void allowRunAs() throws Exception {
    ProjectConfig cfg = projectCache.checkedGet(allProjects).getConfig();
    Util.allow(cfg, GlobalCapability.RUN_AS,
        SystemGroupBackend.getGroup(ANONYMOUS_USERS).getUUID());
    saveProjectConfig(allProjects, cfg);
  }

  private void removeRunAs() throws Exception {
    ProjectConfig cfg = projectCache.checkedGet(allProjects).getConfig();
    Util.remove(cfg, GlobalCapability.RUN_AS,
        SystemGroupBackend.getGroup(ANONYMOUS_USERS).getUUID());
    saveProjectConfig(allProjects, cfg);
  }

  private static Header runAsHeader(Object user) {
    return new BasicHeader("X-Gerrit-RunAs", user.toString());
  }
}