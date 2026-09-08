# smbj resolves its SMB2 message and authenticator classes reflectively.
-keep class com.hierynomus.** { *; }

# smbj's event bus (mbassador) instantiates handler-invocation classes by reflection with
# a (SubscriptionContext) constructor and reads @Handler annotations; renaming or stripping
# any of it crashes the first SMB connection with "did not specify the necessary
# constructor".
-keep class net.engio.mbassy.** { *; }
-keepattributes RuntimeVisibleAnnotations

# smbj's optional code paths reference APIs that do not exist on Android and are never
# taken here: mbassador's javax.el filter (we use no EL expressions) and the SPNEGO /
# Kerberos authenticator (we authenticate with NTLM). R8 must not fail the build over
# classes those dormant paths would need.
-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid
