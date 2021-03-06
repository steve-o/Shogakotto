/* Analytic query.
 */

package com.thomsonreuters.Shogakotto;

import com.reuters.rfa.common.Handle;
import com.google.gson.Gson;

public class Analytic {
	private String service;
	private String app;
	private String query;
	private String item;

	public Analytic (String service, String app, String query, String item) {
		this.setService (service);
		this.setApp (app);
		this.setQuery (query);
		this.setItem (item);
	}

	public String getService() {
		return this.service;
	}

	public void setService (String service) {
		this.service = service;
	}

	public String getApp() {
		return this.app;
	}

	public void setApp (String app) {
		this.app = app;
	}

	public String getQuery() {
		return this.query;
	}

	public void setQuery (String query) {
		this.query = query;
	}

	public String getItem() {
		return this.item;
	}

	public void setItem (String item) {
		this.item = item;
	}

	@Override
	public String toString() {
		return new Gson().toJson (this);
	}
}

/* eof */
