package org.lumongo.client.command.base;

import org.lumongo.client.result.Result;

public abstract class SimpleCommand<S, R extends Result> extends Command<R> {

	public abstract S getRequest();

}
