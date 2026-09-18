package com.safelevelups;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SafeLevelUpsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(SafeLevelUpsPlugin.class);
		RuneLite.main(args);
	}
}
