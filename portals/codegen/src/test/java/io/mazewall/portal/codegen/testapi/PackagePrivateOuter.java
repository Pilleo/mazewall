package io.mazewall.portal.codegen.testapi;

class PackagePrivateOuter {
    public static class PublicInner {
        private final int count;

        public PublicInner(int count) {
            this.count = count;
        }

        public int getCount() {
            return count;
        }
    }

    public interface EnclosedService {
        int echo(PublicInner dto);
    }
}
