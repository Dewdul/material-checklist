package com.materialchecklist;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class MaterialChecklistPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(MaterialChecklistPlugin.class);
		RuneLite.main(args);
	}
}
