/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.core.ccr.action;

import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.MasterNodeRequest;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;

import java.io.IOException;
import java.util.Objects;

public class PauseFollowAction extends Action<AcknowledgedResponse> {

    public static final PauseFollowAction INSTANCE = new PauseFollowAction();
    public static final String NAME = "cluster:admin/xpack/ccr/pause_follow";

    private PauseFollowAction() {
        super(NAME);
    }

    @Override
    public AcknowledgedResponse newResponse() {
        throw new UnsupportedOperationException("usage of Streamable is to be replaced by Writeable");
    }

    @Override
    public Writeable.Reader<AcknowledgedResponse> getResponseReader() {
        return AcknowledgedResponse::new;
    }

    public static class Request extends MasterNodeRequest<Request> {

        private final String followIndex;

        public Request(String followIndex) {
            this.followIndex = Objects.requireNonNull(followIndex, "followIndex");
        }

        public Request(StreamInput in) throws IOException {
            super(in);
            this.followIndex = in.readString();
        }

        public String getFollowIndex() {
            return followIndex;
        }

        @Override
        public ActionRequestValidationException validate() {
            return null;
        }

        @Override
        public void writeTo(final StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(followIndex);
        }
    }

}
