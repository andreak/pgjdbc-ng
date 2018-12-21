/**
 * Copyright (c) 2013, impossibl.com
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *  * Neither the name of impossibl.com nor the names of its contributors may
 *    be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.impossibl.postgres.protocol;

import com.impossibl.postgres.types.Type;
import com.impossibl.postgres.types.TypeRef;

import java.io.IOException;
import java.util.List;

import io.netty.buffer.ByteBuf;

public interface RequestExecutor {

  interface ErrorHandler {

    void handleError(Throwable cause, List<Notice> notices) throws IOException;

  }

  /*****
   * Query requests. Directly execute unparsed SQL text.
   */


  /**
   * Request handler interface for the {@link #query(String, SimpleQueryHandler)} request.
   */
  interface SimpleQueryHandler extends ErrorHandler {

    void handleComplete(String command, Long rowsAffected, Long insertedOid, TypeRef[] parameterTypes, ResultField[] resultFields, RowDataSet rows, List<Notice> notices) throws IOException;
    void handleReady() throws IOException;

  }


  /**
   * Uses the "simple" query protocol to execute the given query.
   *
   * The SQL provided may contain multiple queries concatenated with
   * a semi-colon. Each separate query will produce a single
   * {@link SimpleQueryHandler#handleComplete(String, Long, Long, TypeRef[], ResultField[], RowDataSet, List)}`
   * or
   * {@link SimpleQueryHandler#handleError(Throwable, List)}
   * callback in the provided handler. After all
   * queries have been completed a
   * {@link SimpleQueryHandler#handleReady()} is issued.
   *
   * @param sql SQL query or queries to execute.
   * @param handler Query handler to process results. Can produce multiple
   *                {@link SimpleQueryHandler#handleComplete(String, Long, Long, TypeRef[], ResultField[], RowDataSet, List)}
   *                or
   *                {@link SimpleQueryHandler#handleError(Throwable, List)}
   *                callbacks. Followed by a final
   *                {@link SimpleQueryHandler#handleReady()}
   *                callback when all requests are complete.
   * @throws IOException If an error occurs submitting the request.
   */
  void query(String sql, SimpleQueryHandler handler) throws IOException;


  /**
   * Request handler interface for the
   * {@link #query(String, String, FieldFormatRef[], ByteBuf[], FieldFormatRef[], int, QueryHandler)}
   * request.
   */
  interface QueryHandler extends ErrorHandler {

    void handleComplete(String command, Long rowsAffected, Long insertedOid, TypeRef[] parameterTypes, ResultField[] resultFields, RowDataSet rows, List<Notice> notices) throws IOException;
    void handleSuspend(TypeRef[] parameterTypes, ResultField[] resultFields, RowDataSet rows, List<Notice> notices) throws IOException;

  }

  /**
   * Uses the "extended" query protocol to execute the given query. The
   * requests can pass parameters and result formats.
   *
   * The SQL can only contain a single SQL query and thus the will only
   * produce a single
   * {@link QueryHandler#handleComplete(String, Long, Long, TypeRef[], ResultField[], RowDataSet, List)},
   * {@link QueryHandler#handleSuspend(TypeRef[], ResultField[], RowDataSet, List)}
   * or
   * {@link QueryHandler#handleError(Throwable, List)}
   * callback.
   *
   * Setting {@code maxRows} to anything greater than zero enables suspend/resume
   * functionality via portals. Results are delivered in groups of {@code maxRows} via the
   * {@link QueryHandler#handleSuspend(TypeRef[], ResultField[], RowDataSet, List)}
   * callback.
   * After the first group is received the
   * {@link #resume(String, int, ExecuteHandler)}
   * request can be used to receive the next group of rows.
   *
   * A unique {@code portalName} is required if you wish to have multiple portals
   * open simultaneously. If not, you can pass {@code null} and an unnamed portal will
   * be used; it will destroy any current use of that portal. Anytime you use
   * a named portal it must be closed with a
   * {@link #close(ServerObjectType, String)}
   * request.
   *
   * @param sql SQL query to execute.
   * @param portalName Name of the portal to instantiate or {@code null} to use the unnamed portal.
   * @param parameterFormats Formats (text or binary) of parameters in `parameterBuffers`. Must match the number of
   *                         `parameterBuffers` provided.
   * @param parameterBuffers Buffer of encoded parameter values.
   * @param resultFieldFormats Desired formats of the result fields. Passing an empty array will request all binary
   *                           format parameters. Passing any number of formats less than the number of result
   *                           fields will cause the last format provided to be repeated.
   * @param maxRows The number of results to receive at a time, or zero to receive all results at once. Anything
   *                other than zero instantiates a portal.
   * @param handler Query handler to process results. Will produce a single
   *                {@link QueryHandler#handleComplete(String, Long, Long, TypeRef[], ResultField[], RowDataSet, List)},
   *                {@link QueryHandler#handleSuspend(TypeRef[], ResultField[], RowDataSet, List)}
   *                or
   *                {@link QueryHandler#handleError(Throwable, List)}
   *                callback.
   * @throws IOException If an error occurs submitting the request.
   */
  void query(String sql, String portalName,
             FieldFormatRef[] parameterFormats, ByteBuf[] parameterBuffers,
             FieldFormatRef[] resultFieldFormats, int maxRows, QueryHandler handler) throws IOException;


  /*****
   * Prepare. Prepares a named query for execution.
   *****/


  /**
   * Request handler interface for th `prepare(sql,statementName,parameterTypes,PrepareHandler)` request.
   */
  interface PrepareHandler extends ErrorHandler {

    void handleComplete(TypeRef[] parameterTypes, ResultField[] resultFields, List<Notice> notices) throws IOException;

  }

  /**
   * Prepares a query for later, possibly repeated, execution via the
   * {@link #execute(String, String, FieldFormatRef[], ByteBuf[], FieldFormatRef[], int, ExecuteHandler)}
   * request.
   *
   * The SQL can only contain a single SQL query. Any text that contains multiple
   * queries will be rejected with an error.
   *
   * If {@code statementName} is provided the query will be saved with that name and can be executed
   * repeatedly until it is closed. If {@code null} is provided as the statement name it will save it
   * under the "unnamed" statement and can be used until another prepare request is made using
   * the "unnamed" statement. All named statements should be closed via a
   * {@link #close(ServerObjectType, String)}
   * request when they are no longer in use.
   *
   * {@code parameterTypes} directs the server on what types of parameters are intended to correspond to
   * parameter placeholders in the SQL text. One or all parameter types can be omitted by passing
   * less than the number of parameters in the query or passing {@code null} as the parameter type. The server
   * will attempt to infer the proper type for any parameters without provided types.
   *
   * @param sqlText SQL text to parse; containing a maximum of one query.
   * @param statementName Name of the to-be-parsed sql or {@code null} to use the unnamed statement. All named
   *                      statements should be closed when no longer in use.
   * @param parameterTypes Parameter types corresponding to parameters placeholders in the query string.
   * @param handler Handler to process results of the request. Will produce a single
   *                {@link PrepareHandler#handleComplete(TypeRef[], ResultField[], List)}
   *                or
   *                {@link PrepareHandler#handleError(Throwable, List)}
   *                callback.
   * @throws IOException If an error occurs submitting the request.
   */
  void prepare(String statementName, String sqlText, Type[] parameterTypes, PrepareHandler handler) throws IOException;


  /*****
   * Execute. Execute a previously prepared query & allows resuming suspended queries.
   *****/


  /**
   * Request handler interface for the
   * {@link #execute(String, String, FieldFormatRef[], ByteBuf[], FieldFormatRef[], int, ExecuteHandler)}
   * request.
   */
  interface ExecuteHandler extends ErrorHandler {

    void handleComplete(String command, Long rowsAffected, Long insertedOid, RowDataSet rows, List<Notice> notices) throws IOException;
    void handleSuspend(RowDataSet rows, List<Notice> notices) throws IOException;

  }
  /**
   * Uses the "extended" query protocol to execute a previously prepared query. The
   * requests can pass parameters and result formats.
   *
   * Setting {@code maxRows} to anything greater than zero enables suspend/resume
   * functionality via portals. Results are delivered in groups of {@code maxRows} via the
   * {@link QueryHandler#handleSuspend(TypeRef[], ResultField[], RowDataSet, List)}
   * callback.
   * After the first group is received the
   * {@link #resume(String, int, ExecuteHandler)}
   * request can be used to receive the next group of rows.
   *
   * A unique {@code portalName} is required if you wish to have multiple portals
   * open simultaneously. If not, you can pass {@code null} and an unnamed portal will
   * be used; it will destroy any current use of that portal. Anytime you use
   * a named portal it must be closed with a
   * {@link #close(ServerObjectType, String)}
   * request.
   *
   * @param portalName Name of the portal to instantiate or {@code null} to use the unnamed portal.
   * @param statementName Name of the statement to execute or {@code null} to execute the unnamed statement.
   * @param parameterFormats Formats (text or binary) of parameters in `parameterBuffers`. Must match the number of
   *                         `parameterBuffers` provided.
   * @param parameterBuffers Buffer of encoded parameter values.
   * @param resultFieldFormats Desired formats of the result fields. Passing an empty array will request all binary
   *                           format parameters. Passing any number of formats less than the number of result
   *                           fields will cause the last format provided to be repeated.
   * @param maxRows The number of results to receive at a time, or zero to receive all results at once. Anything
   *                other than zero instantiates a portal.
   * @param handler Execute handler to process results. Will produce a single
   *                {@link ExecuteHandler#handleComplete(String, Long, Long, RowDataSet, List)},
   *                {@link ExecuteHandler#handleSuspend(RowDataSet, List)} or
   *                {@link ExecuteHandler#handleError(Throwable, List)}
   *                callback.
   * @throws IOException If an error occurs submitting the request.
   */
  void execute(String portalName, String statementName,
               FieldFormatRef[] parameterFormats, ByteBuf[] parameterBuffers,
               FieldFormatRef[] resultFieldFormats, int maxRows,
               ExecuteHandler handler) throws IOException;

  /**
   * Resumes a portal previously instantiated via an
   * {@link #execute(String, String, FieldFormatRef[], ByteBuf[], FieldFormatRef[], int, ExecuteHandler)}
   * or
   * {@link #query(String, String, FieldFormatRef[], ByteBuf[], FieldFormatRef[], int, QueryHandler)}
   * request.
   *
   * A portal can be resumed until no more rows are available. As long as more rows are available
   * the request will complete with a
   * {@link ExecuteHandler#handleSuspend(RowDataSet, List)}
   * callback. When no more rows are available the request will complete with a
   * {@link ExecuteHandler#handleComplete(String, Long, Long, RowDataSet, List)}
   * callback.
   *
   * @param portalName Name of the portal to resume or {@code null} to resume the unnamed portal.
   * @param maxRows Max number of rows to return from this request.
   * @param handler Execute handler to process results. Will produce a single
   *                {@link ExecuteHandler#handleComplete(String, Long, Long, RowDataSet, List)},
   *                {@link ExecuteHandler#handleSuspend(RowDataSet, List)} or
   *                {@link ExecuteHandler#handleError(Throwable, List)}
   *                callback.
   * @throws IOException If an error occurs submitting the request.
   */
  void resume(String portalName, int maxRows, ExecuteHandler handler) throws IOException;


  /*****
   * Function Call
   *****/


  /**
   * Request handler interface for the
   * {@link #call(int, FieldFormatRef[], ByteBuf[], FunctionCallHandler)}
   * request.
   */
  interface FunctionCallHandler extends ErrorHandler {

    void handleComplete(ByteBuf result, List<Notice> notices);

  }

  /**
   * Invokes the function specified by {@code functionId} and returns its
   * results.
   *
   * This request is essentially deprecated by PostgreSQL and included here
   * for completeness.
   *
   * @param functionId Function to invoke; specified by its OID.
   * @param parameterFormats Formats (text or binary) of parameters in `parameterBuffers`. Must match the number of
   *                         `parameterBuffers` provided.
   * @param parameterBuffers Buffer of encoded parameter values.
   * @param handler Function call handler to process results. Will produce a single
   *                {@link FunctionCallHandler#handleComplete(ByteBuf, List)}
   *                or
   *                {@link FunctionCallHandler#handleError(Throwable, List)}
   *                callback.
   * @throws IOException If an error occurs submitting the request.
   */
  void call(int functionId,
            FieldFormatRef[] parameterFormats, ByteBuf[] parameterBuffers,
            FunctionCallHandler handler) throws IOException;


  /*****
   * Utility requests. Generally these are "fire and forget" with no completion handlers.
   */


  /**
   * Closes a `Portal` or `Statement` object for the connection.
   *
   * Closing a `Portal` sends a `sync` message to ensure proper
   * transaction demarcation and the request is sent immediately.
   *
   * Closing a statement does not send a sync message and does not attempt
   * to send the request immediately.
   *
   * @param objectType Type of object `Portal` or `Statement` to close
   * @param objectName Name of the object to close.
   * @throws IOException If an error occurs submitting the request.
   */
  void close(ServerObjectType objectType, String objectName) throws IOException;

  /**
   * Executes a statement at the earliest convenience. The submitter has no
   * ability to wait for or monitor the status of the request. All notices and
   * errors are reported to whatever request handler is submitted after it.
   *
   * The best example of using this is beginning a transaction. The request can be
   * sent and will be reported on the following request; including errors that would
   * stop the following request from executing.
   *
   * Although it uses bind/execute, it currently doesn't support passing parameters
   * to the bind, nor receiving any results from a response.
   *
   * @param statementName Name of statement to execute.
   * @throws IOException If an error occurs submitting the request.
   */
  void lazyExecute(String statementName) throws IOException;


  /*****
   * Asynchronous Notification
   */

  interface NotificationHandler {

    void handleNotification(int processId, String channelName, String payload) throws IOException;

  }

}