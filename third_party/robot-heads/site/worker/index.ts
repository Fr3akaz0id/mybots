// The playground is static: its assets are served straight from the build,
// with unknown paths falling back to the app. Anything that reaches the
// Worker itself is not a page.
export default {
  fetch() {
    return new Response(null, { status: 404 });
  },
} satisfies ExportedHandler<Env>;
